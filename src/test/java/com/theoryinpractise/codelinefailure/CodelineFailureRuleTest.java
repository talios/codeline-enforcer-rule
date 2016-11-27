package com.theoryinpractise.codelinefailure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import javaslang.collection.HashSet;
import javaslang.collection.Set;
import org.testng.annotations.Test;

import java.io.FileNotFoundException;
import java.util.regex.Pattern;

import static com.theoryinpractise.codelinefailure.CodelineFailureRule.fieldName;

public class CodelineFailureRuleTest {

  @Test(enabled = false)
  public void testPrivates() throws FileNotFoundException {

    CompilationUnit cu =
        JavaParser.parse(
            "package test;"
                + ""
                + "public class Test {"
                + "  private static String unusedField = \"\";"
                + "  public String pubField = \"\";"
                +
                //            "  public String pubField = unusedField;" +
                "}"
                + "");

    ClassOrInterfaceDeclaration classDef = cu.getClassByName("Test");
    Set<FieldDeclaration> privateFields = HashSet.ofAll(classDef.getFields()).filter(NodeWithModifiers::isPrivate);

    Set<FieldDeclaration> unusedPrivateFieldNames =
        privateFields.filter(
            f -> {
              Pattern usage = Pattern.compile(fieldName(f) + "(\\.|;|\\))");
              return classDef.getChildNodes().stream().noneMatch(node -> usage.matcher(node.toString()).find());
            });

    if (!unusedPrivateFieldNames.isEmpty()) {
      for (FieldDeclaration field : unusedPrivateFieldNames) {
        System.out.println("Unused field found: " + fieldName(field));
      }
      throw new AssertionError("Unused privates found.");
    }
  }
}