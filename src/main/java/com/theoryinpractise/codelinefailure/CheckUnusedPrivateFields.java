package com.theoryinpractise.codelinefailure;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import io.vavr.collection.List;
import io.vavr.control.Validation;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.theoryinpractise.codelinefailure.CodelineFailureRule.checkFiles;
import static com.theoryinpractise.codelinefailure.CodelineFailureRule.nodeName;
import static com.theoryinpractise.codelinefailure.CodelineFailureRule.relativePathOfFile;
import static com.theoryinpractise.codelinefailure.ParserSupport.parseCompilationUnit;
import static io.vavr.control.Validation.invalid;
import static io.vavr.control.Validation.valid;

public class CheckUnusedPrivateFields {

  public static List<Validation<EnforcerRuleException, File>> checkUnusedPrivates(Log log, Boolean checkPrivates, File srcDir)
      throws IOException, EnforcerRuleException {
    if (!checkPrivates) {
      return List.empty();
    }
    return checkFiles(
        log,
        srcDir,
        "unused privates",
        file -> {
          try {
            Optional<CompilationUnit> optionalCompilationUnit = parseCompilationUnit(file);

            if (optionalCompilationUnit.isPresent()) {
              CompilationUnit cu = optionalCompilationUnit.get();

              String primaryClassName = file.getName().replaceFirst("\\.java", "");

              Optional<ClassOrInterfaceDeclaration> classDef = cu.getClassByName(primaryClassName);
              if (!classDef.isPresent()) {
                classDef = cu.getInterfaceByName(primaryClassName);
              }

              return classDef
                  .map(
                      def -> {
                        List<Node> privateFields = List.narrow(List.ofAll(def.getFields()).filter(FieldDeclaration::isPrivate));
                        List<Node> privateMethods = List.narrow(List.ofAll(def.getMethods()).filter(MethodDeclaration::isPrivate));
                        List<Node> allNodes =
                            List.ofAll(def.getChildNodesByType(Node.class))
                                .filter(
                                    node ->
                                        !(node instanceof MethodDeclaration
                                            || node instanceof StringLiteralExpr
                                            || node instanceof SimpleName
                                            || node instanceof NameExpr
                                            || node instanceof LineComment
                                            || node instanceof VariableDeclarator
                                            || node instanceof Parameter
                                            || node instanceof PrimitiveType
                                            || node instanceof ClassOrInterfaceType
                                            || node instanceof BlockStmt));

                        List<Node> unusedPrivateFieldNames = privateFields.filter(f -> detectUnusedFields(allNodes, f));
                        List<Node> unusedPrivateMethods = privateMethods.filter(m -> detectUnusedMethods(allNodes, m));

                        List<Validation<EnforcerRuleException, File>> validations =
                            unusedPrivateFieldNames.appendAll(unusedPrivateMethods).map(node -> invalidateNodeForFile(node, file));
                        return validations;
                      })
                  .orElseGet(() -> List.of(valid(file)));
            } else {
              return List.empty();
            }

          } catch (Exception e) {
            return List.of(invalid(new EnforcerRuleException(String.format("%s: %s", file.getPath(), e.getMessage()))));
          }
        });
  }

  private static boolean detectUnusedMethods(List<Node> allNodes, Node m) {
    String methodName = nodeName(m);
    Pattern usage = Pattern.compile("(::" + methodName + "\\W|" + methodName + "\\()");
    Predicate<Node> usageP = s -> usage.matcher(s.toString()).find();

    return allNodes.toStream().filter(node -> node != m).toJavaStream().noneMatch(usageP);
  }

  private static List<String> findMethodParameterNames(Node node) {
    Optional<Node> parent = node.getParentNode();
    while (parent.isPresent() && !(parent.get() instanceof MethodDeclaration)) {
      parent = parent.get().getParentNode();
    }
    return parent
        .map(
            parentNode -> {
              MethodDeclaration methodDeclaration = (MethodDeclaration) parentNode;
              return List.ofAll(methodDeclaration.getParameters()).map((parameter) -> parameter.getName().toString());
            })
        .orElse(List.empty());
  }

  private static boolean detectUnusedFields(List<Node> allNodes, Node f) {
    String fieldName = nodeName(f);
    Pattern usage = Pattern.compile(String.format("(this\\.%s|(?!\\.)%s)(?:\\.|\\W)", fieldName, fieldName));
    Pattern assignment = Pattern.compile(String.format("(this\\.%s|%s)\\s*=[^=]", fieldName, fieldName));
    Predicate<Node> usageP =
        node -> {
          boolean used = usage.matcher(node.toString()).find();
          return used && !findMethodParameterNames(node).contains(fieldName);
        };
    Predicate<Node> assignmentP = s -> assignment.matcher(s.toString()).find();

    return allNodes.toStream().filter(node -> node != f).filter(assignmentP.negate()).toJavaStream().noneMatch(usageP);
  }

  private static Validation<EnforcerRuleException, File> invalidateNodeForFile(Node node, File file) {
    String relativePathOfFile = relativePathOfFile(file);
    Position begin = node.getBegin().orElse(Position.HOME);
    return invalid(
        new EnforcerRuleException(
            relativePathOfFile,
            String.format("%s:%d:%d - Unused private members found:  %s", file.getName(), begin.line, begin.column, nodeName(node)),
            node.toString()));
  }
}
