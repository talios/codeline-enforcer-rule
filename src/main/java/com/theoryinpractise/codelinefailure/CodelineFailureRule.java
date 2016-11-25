package com.theoryinpractise.codelinefailure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.imports.ImportDeclaration;
import javaslang.Function1;
import javaslang.control.Validation;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import ru.lanwen.verbalregex.VerbalExpression;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.theoryinpractise.codelinefailure.CheckUnusedPrivateFields.checkUnusedPrivates;
import static javaslang.control.Validation.invalid;
import static javaslang.control.Validation.valid;

public class CodelineFailureRule implements EnforcerRule {

  private List<String> patterns = new ArrayList<>();

  private List<String> classes = new ArrayList<>();

  private Boolean checkPrivates = Boolean.FALSE;

  private Log log;

  private static final VerbalExpression unacceptableFiles =
      VerbalExpression.regex().startOfLine().oneOf("\\..*", "#.*", ".*(\\.orig)", ".*(\\.swp)").endOfLine().build();

  private static Predicate<File> fileIsAcceptable = file -> !unacceptableFiles.testExact(file.getName());

  public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
    log = helper.getLog();

    try {
      MavenProject project = (MavenProject) helper.evaluate("${project}");
      File srcDir = new File(project.getBuild().getSourceDirectory());

      Predicate<String> classesPredicate = buildClassPredicate(classes);

      Validation<EnforcerRuleException, File> patternResult = checkPatterns(srcDir, patterns);
      Validation<EnforcerRuleException, File> classesResult = checkClasses(srcDir, classesPredicate);
      Validation<EnforcerRuleException, File> unusedPrivatesResult = checkPrivates ? checkUnusedPrivates(log, srcDir) : Validation.valid(srcDir);

      Validation<javaslang.collection.List<EnforcerRuleException>, File> result =
          Validation.combine(patternResult, classesResult, unusedPrivatesResult).ap((v1, v2, v3) -> srcDir);

      if (result.isInvalid()) {
        javaslang.collection.List<EnforcerRuleException> errors = result.getError();
        for (EnforcerRuleException error : errors) {
          log.error(error.getMessage());
        }

        throw errors.head();
      }

    } catch (ExpressionEvaluationException e) {
      throw new EnforcerRuleException("Unable to lookup an expression " + e.getLocalizedMessage(), e);
    } catch (IOException e) {
      throw new EnforcerRuleException(e.getMessage(), e);
    }
  }

  private Validation<EnforcerRuleException, File> checkClasses(File srcDir, Predicate<String> classesPredicate) throws IOException, EnforcerRuleException {

    return checkFiles(
        log,
        srcDir,
        "classes",
        file -> {
          try {
            CompilationUnit cu = JavaParser.parse(file);

            if (cu.getImports() != null) {
              for (ImportDeclaration importDeclaration : cu.getImports()) {
                String packageName = importDeclaration.getChildNodes().get(0).toString();
                if (classesPredicate.test(packageName)) {
                  return invalid(
                      new EnforcerRuleException(
                          String.format(
                              "%s: Illegal class import - %s at %s:%d:%d is bad!",
                              file.getPath(),
                              packageName,
                              file.getPath(),
                              importDeclaration.getBegin().line,
                              importDeclaration.getBegin().column)));
                }
              }
            }
            return valid(file);
          } catch (Exception e) {
            return invalid(new EnforcerRuleException(String.format("%s: %s", file.getPath(), e.getMessage())));
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

  private Validation<EnforcerRuleException, File> checkPatterns(File srcDir, final List<String> patterns) throws IOException, EnforcerRuleException {
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
                  sb.append("Found pattern " + pattern + " at " + file.getPath() + ":" + reader.getLineNumber());
                  sb.append("\n");
                  sb.append(line);
                  return invalid(new EnforcerRuleException(String.format("%s: %s", file.getPath(), sb.toString())));
                }
              }
            }
            return valid(file);
          } catch (IOException e) {
            log.error(e.getMessage());
            return invalid(new EnforcerRuleException(String.format("%s: %s", file.getPath(), e.getMessage())));
          }
        });
  }

  public static Validation<EnforcerRuleException, File> checkFiles(
      Log log, File srcDir, String type, Function1<File, Validation<EnforcerRuleException, File>> process) throws IOException, EnforcerRuleException {

    if (srcDir == null) return valid(null);

    log.debug("Checking " + type + "  in " + srcDir.getPath());

    File[] files = srcDir.listFiles();

    if (files == null) {
      log.debug("No files found for " + srcDir.getPath());
      return valid(null);
    }

    for (File file : files) {
      if (file.isDirectory()) {
        Validation<EnforcerRuleException, File> result = checkFiles(log, file, type, process);
        if (result.isInvalid()) {
          return result;
        }
      } else {
        if (fileIsAcceptable.test(file)) {
          log.debug("Checking file " + file.getPath());
          Validation<EnforcerRuleException, File> result = process.apply(file);
          if (result.isInvalid()) {
            return result;
          }
        }
      }
    }
    return valid(null);
  }

  public String getCacheId() {
    return null;
  }

  public boolean isCacheable() {
    return false;
  }

  public boolean isResultValid(EnforcerRule arg0) {
    return false;
  }

  @FunctionalInterface
  public interface Process<T> extends Function1<T, EnforcerRuleException> {
    void process(T t) throws EnforcerRuleException;

    @Override
    default EnforcerRuleException apply(@Nullable T input) {
      try {
        process(input);
      } catch (EnforcerRuleException e) {
        return e;
      }
      return null;
    }
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
    return f.getVariable(0).getId().toString();
  }

  static String methodName(MethodDeclaration m) {
    return m.getNameAsString();
  }
}
