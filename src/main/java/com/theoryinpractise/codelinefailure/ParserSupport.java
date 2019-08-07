package com.theoryinpractise.codelinefailure;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Optional;

public class ParserSupport {

  public static Optional<CompilationUnit> parseCompilationUnit(String string) {
    return new JavaParser().parse(string).getResult();
  }

  public static Optional<CompilationUnit> parseCompilationUnit(File file) {
    ParseResult<CompilationUnit> parseResult;

    try {
      parseResult = new JavaParser().parse(file);
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
    return parseResult.getResult();
  }
}
