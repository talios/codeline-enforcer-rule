package com.theoryinpractise.codelinefailure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import io.vavr.Function1;
import io.vavr.collection.List;
import io.vavr.control.Validation;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import ru.lanwen.verbalregex.VerbalExpression;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.theoryinpractise.codelinefailure.CheckUnusedPrivateFields.checkUnusedPrivates;
import static io.vavr.control.Validation.invalid;
import static io.vavr.control.Validation.valid;

public class CodelineFailureRule implements EnforcerRule {

  private java.util.List<String> patterns = new ArrayList<>();

  private java.util.List<String> classes = new ArrayList<>();

  private Boolean checkPrivates = Boolean.FALSE;

  private Log log;

  private static final VerbalExpression unacceptableFiles =
      VerbalExpression.regex()
          .startOfLine()
          .oneOf("\\..*", "#.*", ".*(\\.orig)", ".*(\\.swp)")
          .endOfLine()
          .build();

  private static Predicate<File> fileIsAcceptable =
      file -> !unacceptableFiles.testExact(file.getName());

  @Override
  public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
    log = helper.getLog();

    try {
      MavenProject project = (MavenProject) helper.evaluate("${project}");
      File srcDir = new File(project.getBuild().getSourceDirectory());

      Predicate<String> classesPredicate = buildClassPredicate(List.ofAll(classes));

      List<Validation<EnforcerRuleException, File>> patternResult =
          checkPatterns(srcDir, List.ofAll(patterns));
      List<Validation<EnforcerRuleException, File>> classesResult =
          checkClasses(srcDir, classesPredicate);
      List<Validation<EnforcerRuleException, File>> unusedPrivatesResult =
          checkUnusedPrivates(log, checkPrivates, srcDir);

      List<Validation<EnforcerRuleException, File>> validations =
          patternResult.appendAll(classesResult).appendAll(unusedPrivatesResult);

      List<Validation<EnforcerRuleException, File>> errors =
          validations.filter(Validation::isEmpty).distinct();

      errors
          .groupBy(v -> v.getError().getSource())
          .forEach(
              (source, validations1) -> {
                log.warn("Violations in: " + source);
                for (Validation<EnforcerRuleException, File> validation : validations1) {
                  log.warn("    " + validation.getError().getMessage());
                }
              });

      if (!errors.isEmpty()) {
        throw new EnforcerRuleException(
            String.format("%s code enforcer violations found - check build log.", errors.length()));
      }

    } catch (ExpressionEvaluationException e) {
      throw new EnforcerRuleException(
          "Unable to lookup an expression " + e.getLocalizedMessage(), e);
    } catch (IOException e) {
      throw new EnforcerRuleException(e.getMessage(), e);
    }
  }

  private List<Validation<EnforcerRuleException, File>> checkClasses(
      File srcDir, Predicate<String> classesPredicate) throws IOException, EnforcerRuleException {

    return checkFiles(
        log,
        srcDir,
        "classes",
        file -> {
          try {
            CompilationUnit cu = JavaParser.parse(file);

            for (ImportDeclaration importDeclaration : cu.getImports()) {
              String packageName = importDeclaration.getChildNodes().get(0).toString();
              if (classesPredicate.test(packageName)) {
                Position begin = importDeclaration.getBegin().orElse(Position.HOME);
                return List.of(
                    invalid(
                        new EnforcerRuleException(
                            relativePathOfFile(file),
                            String.format(
                                "Illegal class import - %s at %s:%d:%d is bad!",
                                packageName, file.getPath(), begin.line, begin.column),
                            "")));
              }
            }
            return List.of(valid(file));
          } catch (Exception e) {
            return List.of(invalid(new EnforcerRuleException(file.getPath(), e.getMessage(), "")));
          }
        });
  }

  private Predicate<String> buildClassPredicate(List<String> classes) {
    return input -> {
      for (String aClass : classes) {
        if (Pattern.compile(aClass).matcher(input).matches()) {
          return true;
        }
      }
      return false;
    };
  }

  private List<Validation<EnforcerRuleException, File>> checkPatterns(
      File srcDir, final List<String> patterns) throws IOException, EnforcerRuleException {
    return checkFiles(
        log,
        srcDir,
        "patterns",
        file -> {
          try {
            LineNumberReader reader = new LineNumberReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
              for (String pattern : patterns) {
                if (Pattern.compile(pattern).matcher(line).find()) {
                  StringBuilder sb = new StringBuilder();
                  sb.append(
                      "Found pattern "
                          + pattern
                          + " at "
                          + file.getPath()
                          + ":"
                          + reader.getLineNumber());
                  sb.append("\n");
                  sb.append(line);
                  return List.of(
                      invalid(
                          new EnforcerRuleException(
                              String.format("%s: %s", file.getPath(), sb.toString()))));
                }
              }
            }
            return List.of(valid(file));
          } catch (IOException e) {
            log.error(e.getMessage());
            return List.of(
                invalid(
                    new EnforcerRuleException(
                        String.format("%s: %s", file.getPath(), e.getMessage()))));
          }
        });
  }

  public static List<Validation<EnforcerRuleException, File>> checkFiles(
      Log log,
      File srcDir,
      String type,
      Function1<File, List<Validation<EnforcerRuleException, File>>> process)
      throws IOException, EnforcerRuleException {
    return checkFiles(List.empty(), log, srcDir, type, process);
  }

  public static List<Validation<EnforcerRuleException, File>> checkFiles(
      List<Validation<EnforcerRuleException, File>> validations,
      Log log,
      File srcDir,
      String type,
      Function1<File, List<Validation<EnforcerRuleException, File>>> process)
      throws IOException, EnforcerRuleException {

    if (srcDir == null) return validations;

    log.debug("Checking " + type + "  in " + srcDir.getPath());

    File[] files = srcDir.listFiles();

    if (files == null) {
      log.debug("No files found for " + srcDir.getPath());
      return validations;
    }

    for (File file : files) {
      if (file.isDirectory()) {
        List<Validation<EnforcerRuleException, File>> result =
            checkFiles(validations, log, file, type, process);
        validations = validations.appendAll(result);
      } else {
        if (fileIsAcceptable.test(file)) {
          log.debug("Checking file " + file.getPath());
          List<Validation<EnforcerRuleException, File>> result = process.apply(file);
          validations = validations.appendAll(result);
        }
      }
    }
    return validations;
  }

  @Override
  public String getCacheId() {
    return null;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  @Override
  public boolean isResultValid(EnforcerRule arg0) {
    return false;
  }

  static String nodeName(Node node) {
    if (node instanceof FieldDeclaration) {
      return fieldName((FieldDeclaration) node);
    }
    if (node instanceof MethodDeclaration) {
      return methodName((MethodDeclaration) node);
    }
    throw new IllegalArgumentException("Unsupported node type)");
  }

  static String fieldName(FieldDeclaration f) {
    return f.getVariable(0).getName().toString();
  }

  static String methodName(MethodDeclaration m) {
    return m.getNameAsString();
  }

  static String relativePathOfFile(File file) {
    String currentPath = new File("").getAbsolutePath();
    if (file.getAbsolutePath().startsWith(currentPath)) {
      return file.getAbsolutePath().substring(currentPath.length() + 1);
    } else {
      return file.getAbsolutePath();
    }
  }
}
