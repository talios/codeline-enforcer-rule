package com.theoryinpractise.codelinefailure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.google.common.collect.ImmutableList;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class CodelineFailureRule implements EnforcerRule {

  private List<String> patterns = new ArrayList<>();

  private List<String> classes = new ArrayList<>();

  private Log log;

  private Predicate<File> fileIsAcceptable = new Predicate<File>() {

      List<Pattern> unacceptablePatterns = ImmutableList.of(
              Pattern.compile("\\..*"),
              Pattern.compile("#.*"),
              Pattern.compile(".*(\\.orig)$"),
              Pattern.compile(".*(\\.swp)$")
      );

      public boolean test(@Nullable File file) {
          String fileName = file.getName();
          for (Pattern unacceptablePattern : unacceptablePatterns) {
              if (unacceptablePattern.matcher(fileName).matches()) {
                  return false;
              }
          }

          return true;
      }
  };

  public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
    log = helper.getLog();

    try {
      // get the various expressions out of the helper.
      MavenProject project = (MavenProject) helper.evaluate("${project}");

      File srcDir = new File(project.getBuild().getSourceDirectory());

      checkPatterns(srcDir, patterns);
      checkClasses(srcDir, classes);

    } catch (ExpressionEvaluationException e) {
      throw new EnforcerRuleException("Unable to lookup an expression " + e.getLocalizedMessage(), e);
    } catch (IOException e) {
      throw new EnforcerRuleException(e.getMessage(), e);
    }
  }

  private void checkClasses(File srcDir, final List<String> classes) throws IOException, EnforcerRuleException {
      
    final Predicate<String> classesPredicate = input -> {
      for (String aClass : classes) {
        if (Pattern.compile(aClass).matcher(input).matches()) {
          return true;
        }
      }
      return false;
    };

    checkFiles(srcDir, "patterns", new Process<File>() {
      @Override
      public void process(@Nullable final File file) throws EnforcerRuleException {

        try {
          CompilationUnit cu = JavaParser.parse(file);

          if (cu.getImports() != null) {
            for (ImportDeclaration importDeclaration : cu.getImports()) {
              if (classesPredicate.test(importDeclaration.getName().toString())) {
                throw new EnforcerRuleException(
                    String.format("%s: Illegal class import - %s at %s:%s is bad!", file.getPath(), importDeclaration.getName().toString(), file.getPath(), importDeclaration.getRange().get().begin.toString()));
              }
            }
          }

        } catch (Exception e) {
          throw new EnforcerRuleException(String.format("%s: %s", file.getPath(), e.getMessage()));
        }
      }
    });
  }

  private void checkPatterns(File srcDir, final List<String> patterns) throws IOException, EnforcerRuleException {
    checkFiles(srcDir, "patterns", new Process<File>() {
      @Override
      public void process(@Nullable File file) throws EnforcerRuleException {
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

                throw new EnforcerRuleException(String.format("%s: %s", file.getPath(), sb.toString()));
              }
            }
          }
        } catch (IOException e) {
          log.error(e.getMessage());
          throw new EnforcerRuleException(String.format("%s: %s", file.getPath(), e.getMessage()));
        }


      }
    });
  }

  private void checkFiles(File srcDir, String type, Process<File> process) throws IOException, EnforcerRuleException {

    if (srcDir == null) return;

    log.debug("Checking " + type + "  in " + srcDir.getPath());

    File[] files = srcDir.listFiles();

    if (files == null) {
      log.debug("No files found for " + srcDir.getPath());
      return;
    }

    for (File file : files) {
      if (file.isDirectory()) {
        checkFiles(file, type, process);
      } else {
        if (fileIsAcceptable.test(file)) {
          log.debug("Checking file " + file.getPath());
          process.process(file);
        }
      }
    }
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

  public abstract static class Process<T> implements Function<T, EnforcerRuleException> {
    abstract void process(T t) throws EnforcerRuleException;

    @Override
    public final EnforcerRuleException apply(@Nullable T input) {
      try {
        process(input);
      } catch (EnforcerRuleException e) {
        return e;
      }
      return null;
    }
  }
}
