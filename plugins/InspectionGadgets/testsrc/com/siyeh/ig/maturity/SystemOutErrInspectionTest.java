/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.maturity;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.siyeh.ig.LightInspectionTestCase;

/**
 * @author Bas Leijdekkers
 */
public class SystemOutErrInspectionTest extends LightInspectionTestCase {

  public void testSimple() {
    doStatementTest("/*Uses of 'System.out' should probably be replaced with more robust logging*/System.out/**/.println(\"debugging\");");
  }

  public void testInTest() {
    addEnvironmentClass("package org.junit;" +
                        "@Retention(RetentionPolicy.RUNTIME) " +
                        "@Target({ElementType.METHOD}) " +
                        "public @interface Test {}");
    doMemberTest("@org.junit.Test public void testSomething() {" +
                 "  System.out.println(\"debugger\");" +
                 "}");
  }

  public void testMultiple() {
    doMemberTest("public void foo() {" +
                 "  /*Uses of 'System.out' should probably be replaced with more robust logging*/System.out/**/.println(0);" +
                 "  /*Uses of 'System.err' should probably be replaced with more robust logging*/System.err/**/.println(0);" +
                 "  final java.io.PrintStream out = /*Uses of 'System.out' should probably be replaced with more robust logging*/System.out/**/;" +
                 "  final java.io.PrintStream err = /*Uses of 'System.err' should probably be replaced with more robust logging*/System.err/**/;" +
                 "}");
  }

  @Override
  protected InspectionProfileEntry getInspection() {
    final SystemOutErrInspection inspection = new SystemOutErrInspection();
    inspection.ignoreInTestCode = true;
    return inspection;
  }
}
