package de.wlsc.junit.converter.plugin.visitor;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.stream.Stream;

public class JUnit4Visitor extends VoidVisitorAdapter<Void> {

  private static final ImmutableMap<String, String> MAPPERS = ImmutableMap.<String, String>builder()
      // imports
      .put("org.junit.Test", "org.junit.jupiter.api.Test")
      .put("org.junit.Before", "org.junit.jupiter.api.BeforeEach")
      .put("org.junit.BeforeClass", "org.junit.jupiter.api.BeforeAll")
      .put("org.junit.After", "org.junit.jupiter.api.AfterEach")
      .put("org.junit.AfterClass", "org.junit.jupiter.api.AfterAll")
      .put("org.junit.Ignore", "org.junit.jupiter.api.Disabled")
      .put("org.junit.Assume", "org.junit.jupiter.api.Assumptions")
      .put("org.junit.Assume.assumeTrue", "org.junit.jupiter.api.Assumptions.assumeTrue")
      .put("org.junit.Assume.assumeFalse", "org.junit.jupiter.api.Assumptions.assumeFalse")
      .put("org.junit.Assert.assertThat", "org.hamcrest.MatcherAssert.assertThat")
      // annotations
      .put("Before", "BeforeEach")
      .put("BeforeClass", "BeforeAll")
      .put("After", "AfterEach")
      .put("AfterClass", "AfterAll")
      .build();

  @Override
  public void visit(final ImportDeclaration importDeclaration, final Void arg) {
    replaceImportIfPresent(importDeclaration);
    super.visit(importDeclaration, arg);
  }

  @Override
  public void visit(final MarkerAnnotationExpr markerAnnotationExpr, final Void arg) {
    replaceAnnotationNameIfPresent(markerAnnotationExpr);
    replaceIgnoreIfPresent(markerAnnotationExpr);
    super.visit(markerAnnotationExpr, arg);
  }

  @Override
  public void visit(final SingleMemberAnnotationExpr singleMemberAnnotationExpr, final Void arg) {
    replaceAnnotationNameIfPresent(singleMemberAnnotationExpr);
    replaceIgnoreWithParameterIfPresent(singleMemberAnnotationExpr);
    super.visit(singleMemberAnnotationExpr, arg);
  }

  @Override
  public void visit(final NormalAnnotationExpr normalAnnotationExpr, final Void arg) {
    replaceAnnotationNameIfPresent(normalAnnotationExpr);
    replaceTestIfPresent(normalAnnotationExpr);
    super.visit(normalAnnotationExpr, arg);
  }

  @Override
  public void visit(final MethodCallExpr methodCallExpr, final Void arg) {
    replaceAssumeTrueFalseIfPresent(methodCallExpr);
    super.visit(methodCallExpr, arg);
  }

  private void replaceAssumeTrueFalseIfPresent(final MethodCallExpr methodCallExpr) {
    String methodName = methodCallExpr.getNameAsString();

    if ("assumeTrue".equals(methodName) || "assumeFalse".equals(methodName)) {
      NodeList<Expression> arguments = methodCallExpr.getArguments();
      if (arguments.size() == 2) {
        methodCallExpr.setArguments(new NodeList<>(arguments.get(1), arguments.get(0)));
      }
      methodCallExpr.getScope()
          .ifPresent(expression -> methodCallExpr.setScope(new NameExpr("Assumptions")));
    }
  }

  public void replaceIgnoreIfPresent(final MarkerAnnotationExpr markerAnnotationExpr) {
    Stream.of(markerAnnotationExpr)
        .filter(expr -> "Ignore".equals(expr.getNameAsString()))
        .map(expr -> new Name("Disabled"))
        .map(MarkerAnnotationExpr::new)
        .forEach(markerAnnotationExpr::replace);
  }

  private void replaceIgnoreWithParameterIfPresent(final SingleMemberAnnotationExpr singleMemberAnnotationExpr) {
    Stream.of(singleMemberAnnotationExpr)
        .filter(expr -> "Ignore".equals(expr.getNameAsString()))
        .map(SingleMemberAnnotationExpr::getMemberValue)
        .map(Expression::asStringLiteralExpr)
        .map(LiteralStringValueExpr::getValue)
        .map(StringLiteralExpr::new)
        .map(value -> new SingleMemberAnnotationExpr(new Name("Disabled"), value))
        .forEach(singleMemberAnnotationExpr::replace);
  }

  private void replaceTestIfPresent(final NormalAnnotationExpr normalAnnotationExpr) {
    if (!"Test".equals(normalAnnotationExpr.getNameAsString())) {
      return;
    }
    normalAnnotationExpr.getParentNode().ifPresent(node -> {
      for (MemberValuePair pair : normalAnnotationExpr.getPairs()) {
        for (Node childNode : node.getChildNodes()) {
          if (childNode instanceof BlockStmt) {
            String identifier = pair.getName().asString();
            if ("timeout".equals(identifier)) {
              wrapWithAssertTimeout((BlockStmt) childNode, pair.getValue());
            }
            if ("expected".equals(identifier)) {
              wrapWithExpected((BlockStmt) childNode, pair.getValue());
            }
          }
        }
      }
    });
    normalAnnotationExpr.replace(new MarkerAnnotationExpr(new Name("Test")));
  }

  private void replaceImportIfPresent(final ImportDeclaration importDeclaration) {
    Optional.ofNullable(MAPPERS.get(importDeclaration.getNameAsString()))
        .map(name -> new ImportDeclaration(name, importDeclaration.isStatic(), false))
        .ifPresent(importDeclaration::replace);
  }

  private void replaceAnnotationNameIfPresent(final AnnotationExpr annotationExpr) {
    Optional.ofNullable(MAPPERS.get(annotationExpr.getNameAsString()))
        .map(Name::new)
        .map(MarkerAnnotationExpr::new)
        .ifPresent(annotationExpr::replace);
  }

  private void wrapWithAssertTimeout(final BlockStmt oldBlockStmt, final Expression annotationValue) {

    BlockStmt previousBlockStmt = new BlockStmt(oldBlockStmt.getStatements());
    MethodCallExpr assertTimeout = new MethodCallExpr("assertTimeout",
        new MethodCallExpr("ofMillis", new LongLiteralExpr(annotationValue.asLongLiteralExpr().asLong())),
        new LambdaExpr(new NodeList<>(), previousBlockStmt));
    Statement timeoutStatement = new ExpressionStmt(assertTimeout);
    oldBlockStmt.setStatements(new NodeList<>(timeoutStatement));

    oldBlockStmt.findCompilationUnit().ifPresent(unit -> {
      unit.addImport("java.time.Duration.ofMillis", true, false);
      unit.addImport("org.junit.jupiter.api.Assertions.assertTimeout", true, false);
    });
  }

  private void wrapWithExpected(final BlockStmt oldBlockStmt, final Expression annotationValue) {

    BlockStmt previousBlockStmt = new BlockStmt(oldBlockStmt.getStatements());
    MethodCallExpr assertTimeout = new MethodCallExpr("assertThrows",
        new ClassExpr(annotationValue.asClassExpr().getType()),
        new LambdaExpr(new NodeList<>(), previousBlockStmt));
    Statement timeoutStatement = new ExpressionStmt(assertTimeout);
    oldBlockStmt.setStatements(new NodeList<>(timeoutStatement));

    oldBlockStmt.findCompilationUnit()
        .ifPresent(compilationUnit ->
            compilationUnit.addImport("org.junit.jupiter.api.Assertions.assertThrows", true, false));
  }
}