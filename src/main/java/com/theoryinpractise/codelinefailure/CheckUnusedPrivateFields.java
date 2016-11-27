package com.theoryinpractise.codelinefailure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import javaslang.collection.List;
import javaslang.control.Validation;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.theoryinpractise.codelinefailure.CodelineFailureRule.checkFiles;
import static com.theoryinpractise.codelinefailure.CodelineFailureRule.nodeName;
import static com.theoryinpractise.codelinefailure.CodelineFailureRule.relativePathOfFile;
import static javaslang.control.Validation.invalid;
import static javaslang.control.Validation.valid;

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
            CompilationUnit cu = JavaParser.parse(file);

            String primaryClassName = file.getName().replaceFirst("\\.java", "");

            ClassOrInterfaceDeclaration classDef = cu.getClassByName(primaryClassName);
            if (classDef == null) {
              classDef = cu.getInterfaceByName(primaryClassName);
            }

            if (classDef != null) {

              List<Node> privateFields = List.narrow(List.ofAll(classDef.getFields()).filter(FieldDeclaration::isPrivate));
              List<Node> privateMethods = List.narrow(List.ofAll(classDef.getMethods()).filter(MethodDeclaration::isPrivate));
              List<Node> allNodes = List.ofAll(classDef.getNodesByType(Node.class));

              List<Node> unusedPrivateFieldNames = privateFields.filter(f -> detectUnusedFields(allNodes, f));
              List<Node> unusedPrivateMethods = privateMethods.filter(m -> detectUnusedMethods(allNodes, m));

              List<Validation<EnforcerRuleException, File>> validations =
                  unusedPrivateFieldNames.appendAll(unusedPrivateMethods).map(node -> invalidateNodeForFile(node, file));
              return validations;
            } else {
              return List.of(valid(file));
            }

          } catch (Exception e) {
            e.printStackTrace();
            return List.of(invalid(new EnforcerRuleException(String.format("%s: %s", file.getPath(), e.getMessage()))));
          }
        });
  }

  private static boolean detectUnusedMethods(List<Node> allNodes, Node m) {
    String methodName = nodeName(m);
    Pattern usage = Pattern.compile("(::" + methodName + "\\)|" + methodName + "\\()");
    Predicate<Node> usageP = s -> usage.matcher(s.toString()).find();

    return allNodes.toStream().filter(node -> node != m).toJavaStream().noneMatch(usageP);
  }

  private static boolean detectUnusedFields(List<Node> allNodes, Node f) {
    Pattern usage = Pattern.compile("(this\\." + nodeName(f) + "|(?!\\.)" + nodeName(f) + ")(?:\\.|\\W)");
    Pattern assignment = Pattern.compile(nodeName(f) + "\\s*=[^=]");
    Predicate<Node> usageP = s -> usage.matcher(s.toString()).find();
    Predicate<Node> assignmentP = s -> assignment.matcher(s.toString()).find();

    return allNodes.toStream().filter(node -> node != f).filter(assignmentP.negate()).toJavaStream().noneMatch(usageP);
  }

  private static Validation<EnforcerRuleException, File> invalidateNodeForFile(Node node, File file) {
    String relativePathOfFile = relativePathOfFile(file);
    return invalid(
        new EnforcerRuleException(
            relativePathOfFile,
            String.format(
                "Unused private members found - %s at %s:%d:%d is bad!", nodeName(node), relativePathOfFile, node.getBegin().line, node.getBegin().column),
            node.toString()));
  }
}
