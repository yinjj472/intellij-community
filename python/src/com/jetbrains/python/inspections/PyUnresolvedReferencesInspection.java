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
package com.jetbrains.python.inspections;

import com.google.common.collect.ImmutableSet;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ui.ListEditForm;
import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.*;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Consumer;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.PyCodeInsightSettings;
import com.jetbrains.python.codeInsight.PyDynamicMember;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil;
import com.jetbrains.python.codeInsight.imports.AutoImportHintAction;
import com.jetbrains.python.codeInsight.imports.AutoImportQuickFix;
import com.jetbrains.python.codeInsight.imports.OptimizeImportsQuickFix;
import com.jetbrains.python.codeInsight.imports.PythonReferenceImporter;
import com.jetbrains.python.console.PydevConsoleRunner;
import com.jetbrains.python.documentation.DocStringParameterReference;
import com.jetbrains.python.documentation.DocStringTypeReference;
import com.jetbrains.python.inspections.quickfix.*;
import com.jetbrains.python.packaging.PyPIPackageUtil;
import com.jetbrains.python.packaging.PyPackageManager;
import com.jetbrains.python.packaging.PyRequirement;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyImportStatementNavigator;
import com.jetbrains.python.psi.impl.PyImportedModule;
import com.intellij.psi.util.QualifiedName;
import com.jetbrains.python.psi.impl.references.PyImportReference;
import com.jetbrains.python.psi.impl.references.PyOperatorReference;
import com.jetbrains.python.psi.resolve.ImportedResolveResult;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.QualifiedNameFinder;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import com.jetbrains.python.psi.types.*;
import com.jetbrains.python.sdk.PythonSdkType;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

import static com.jetbrains.python.inspections.quickfix.AddIgnoredIdentifierQuickFix.END_WILDCARD;

/**
 * Marks references that fail to resolve. Also tracks unused imports and provides "optimize imports" support.
 * User: dcheryasov
 * Date: Nov 15, 2008
 */
public class PyUnresolvedReferencesInspection extends PyInspection {
  private static Key<Visitor> KEY = Key.create("PyUnresolvedReferencesInspection.Visitor");
  public static final Key<PyUnresolvedReferencesInspection> SHORT_NAME_KEY = Key.create(PyUnresolvedReferencesInspection.class.getSimpleName());

  public JDOMExternalizableStringList ignoredIdentifiers = new JDOMExternalizableStringList();

  public static PyUnresolvedReferencesInspection getInstance(PsiElement element) {
    final InspectionProfile inspectionProfile = InspectionProjectProfileManager.getInstance(element.getProject()).getInspectionProfile();
    return (PyUnresolvedReferencesInspection)inspectionProfile.getUnwrappedTool(SHORT_NAME_KEY.toString(), element);
  }

  @Nls
  @NotNull
  public String getDisplayName() {
    return PyBundle.message("INSP.NAME.unresolved.refs");
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                        final boolean isOnTheFly,
                                        @NotNull final LocalInspectionToolSession session) {
    final Visitor visitor = new Visitor(holder, session, ignoredIdentifiers);
    // buildVisitor() will be called on injected files in the same session - don't overwrite if we already have one
    final Visitor existingVisitor = session.getUserData(KEY);
    if (existingVisitor == null) {
      session.putUserData(KEY, visitor);
    }
    return visitor;
  }

  @Override
  public void inspectionFinished(@NotNull LocalInspectionToolSession session, @NotNull ProblemsHolder holder) {
    final Visitor visitor = session.getUserData(KEY);
    assert visitor != null;
    if (PyCodeInsightSettings.getInstance().HIGHLIGHT_UNUSED_IMPORTS) {
      visitor.highlightUnusedImports();
    }
    session.putUserData(KEY, null);
  }

  @Override
  public JComponent createOptionsPanel() {
    ListEditForm form = new ListEditForm("Ignore references", ignoredIdentifiers);
    return form.getContentPanel();
  }

  public static class Visitor extends PyInspectionVisitor {
    private Set<PsiElement> myUsedImports = Collections.synchronizedSet(new HashSet<PsiElement>());
    private Set<NameDefiner> myAllImports = Collections.synchronizedSet(new HashSet<NameDefiner>());
    private final ImmutableSet<String> myIgnoredIdentifiers;

    public Visitor(@Nullable ProblemsHolder holder, @NotNull LocalInspectionToolSession session, List<String> ignoredIdentifiers) {
      super(holder, session);
      myIgnoredIdentifiers = ImmutableSet.copyOf(ignoredIdentifiers);
    }

    @Override
    public void visitPyTargetExpression(PyTargetExpression node) {
      checkSlots(node);
    }

    private void checkSlots(PyQualifiedExpression node) {
      final PyExpression qualifier = node.getQualifier();
      if (qualifier != null) {
        final PyType type = myTypeEvalContext.getType(qualifier);
        if (type instanceof PyClassType) {
          final PyClass pyClass = ((PyClassType)type).getPyClass();
          if (pyClass.isNewStyleClass()) {
            if (pyClass.getOwnSlots() == null) {
              return;
            }
            final List<String> slots = pyClass.getSlots();
            final String attrName = node.getReferencedName();
            if (slots != null && !slots.contains(attrName) && !slots.contains(PyNames.DICT)) {
              for (PyClass ancestor : pyClass.getAncestorClasses(myTypeEvalContext)) {
                if (ancestor == null) {
                  return;
                }
                if (PyNames.OBJECT.equals(ancestor.getName())) {
                  break;
                }
                final List<String> ancestorSlots = ancestor.getSlots();
                if (ancestorSlots == null || ancestorSlots.contains(attrName) || ancestorSlots.contains(PyNames.DICT)) {
                  return;
                }
              }
              final ASTNode nameNode = node.getNameElement();
              final PsiElement e = nameNode != null ? nameNode.getPsi() : node;
              registerProblem(e, "'" + pyClass.getName() + "' object has no attribute '" + attrName + "'");
            }
          }
        }
      }
    }

    @Override
    public void visitPyImportElement(PyImportElement node) {
      super.visitPyImportElement(node);
      final PyFromImportStatement fromImport = PsiTreeUtil.getParentOfType(node, PyFromImportStatement.class);
      if (fromImport == null || !fromImport.isFromFuture()) {
        myAllImports.add(node);
      }
    }

    @Override
    public void visitPyStarImportElement(PyStarImportElement node) {
      super.visitPyStarImportElement(node);
      myAllImports.add(node);
    }

    @Nullable
    private static PyExceptPart getImportErrorGuard(PyElement node) {
      final PyImportStatementBase importStatement = PsiTreeUtil.getParentOfType(node, PyImportStatementBase.class);
      if (importStatement != null) {
        final PyTryPart tryPart = PsiTreeUtil.getParentOfType(node, PyTryPart.class);
        if (tryPart != null) {
          final PyTryExceptStatement tryExceptStatement = PsiTreeUtil.getParentOfType(tryPart, PyTryExceptStatement.class);
          if (tryExceptStatement != null) {
            for (PyExceptPart exceptPart : tryExceptStatement.getExceptParts()) {
              final PyExpression expr = exceptPart.getExceptClass();
              if (expr != null && "ImportError".equals(expr.getName())) {
                return exceptPart;
              }
            }
          }
        }
      }
      return null;
    }

    private static boolean isGuardedByHasattr(@NotNull final PyElement node, @NotNull final String name) {
      final String nodeName = node.getName();
      if (nodeName != null) {
        final ScopeOwner owner = ScopeUtil.getDeclarationScopeOwner(node, nodeName);
        PyElement e = PsiTreeUtil.getParentOfType(node, PyConditionalStatementPart.class, PyConditionalExpression.class);
        while (e != null && PsiTreeUtil.isAncestor(owner, e, true)) {
          final ArrayList<PyCallExpression> calls = new ArrayList<PyCallExpression>();
          PyExpression cond = null;
          if (e instanceof PyConditionalStatementPart) {
            cond = ((PyConditionalStatementPart)e).getCondition();
          }
          else if (e instanceof PyConditionalExpression && PsiTreeUtil.isAncestor(((PyConditionalExpression)e).getTruePart(), node, true)) {
            cond = ((PyConditionalExpression)e).getCondition();
          }
          if (cond instanceof PyCallExpression) {
            calls.add((PyCallExpression)cond);
          }
          if (cond != null) {
            final PyCallExpression[] callExprs = PsiTreeUtil.getChildrenOfType(cond, PyCallExpression.class);
            if (callExprs != null) {
              calls.addAll(Arrays.asList(callExprs));
            }
            for (PyCallExpression call : calls) {
              final PyExpression callee = call.getCallee();
              final PyExpression[] args = call.getArguments();
              // TODO: Search for `node` aliases using aliases analysis
              if (callee != null && "hasattr".equals(callee.getName()) && args.length == 2 &&
                  nodeName.equals(args[0].getName()) && args[1] instanceof PyStringLiteralExpression &&
                  ((PyStringLiteralExpression)args[1]).getStringValue().equals(name)) {
                return true;
              }
            }
          }
          e = PsiTreeUtil.getParentOfType(e, PyConditionalStatementPart.class);
        }
      }
      return false;
    }

    @Override
    public void visitPyElement(final PyElement node) {
      super.visitPyElement(node);
      final PsiFile file = node.getContainingFile();
      final InjectedLanguageManager injectedLanguageManager =
        InjectedLanguageManager.getInstance(node.getProject());
      if (injectedLanguageManager.isInjectedFragment(file)) {
        final PsiLanguageInjectionHost host =
          injectedLanguageManager.getInjectionHost(node);
        processInjection(host);
      }
      if (node instanceof PyReferenceOwner) {
        final PyResolveContext resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(myTypeEvalContext);
        processReference(node, ((PyReferenceOwner)node).getReference(resolveContext));
      }
      else {
        if (node instanceof PsiLanguageInjectionHost) {
          processInjection((PsiLanguageInjectionHost)node);
        }
        for (final PsiReference reference : node.getReferences()) {
          processReference(node, reference);
        }
      }
    }

    private void processInjection(@Nullable PsiLanguageInjectionHost node) {
      if (node == null) return;
      final List<Pair<PsiElement,TextRange>>
        files = InjectedLanguageManager.getInstance(node.getProject()).getInjectedPsiFiles(node);
      if (files != null) {
        for (Pair<PsiElement,TextRange> pair : files) {
          new PyRecursiveElementVisitor() {
            @Override
            public void visitPyElement(PyElement element) {
              super.visitPyElement(element);
              if (element instanceof PyReferenceOwner) {
                final PyResolveContext resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(myTypeEvalContext);
                final PsiPolyVariantReference reference = ((PyReferenceOwner)element).getReference(resolveContext);
                if (reference != null) {
                  final ResolveResult[] resolveResults = reference.multiResolve(false);
                  for (ResolveResult resolveResult : resolveResults) {
                    if (resolveResult instanceof ImportedResolveResult) {
                      myUsedImports.addAll(((ImportedResolveResult)resolveResult).getNameDefiners());
                    }
                  }
                }
              }
            }
          }.visitElement(pair.getFirst());
        }
      }
    }

    private void processReference(PyElement node, @Nullable PsiReference reference) {
      if (reference == null || reference.isSoft()) return;
      HighlightSeverity severity = HighlightSeverity.ERROR;
      if (reference instanceof PsiReferenceEx) {
        severity = ((PsiReferenceEx)reference).getUnresolvedHighlightSeverity(myTypeEvalContext);
        if (severity == null) return;
      }
      PyExceptPart guard = getImportErrorGuard(node);
      if (guard != null) {
        processReferenceInImportGuard(node, guard);
        return;
      }
      if (node instanceof PyQualifiedExpression) {
        final PyQualifiedExpression qExpr = (PyQualifiedExpression)node;
        final PyExpression qualifier = qExpr.getQualifier();
        final String name = node.getName();
        if (qualifier != null && name != null && isGuardedByHasattr(qualifier, name)) {
          return;
        }
      }
      PsiElement target = null;
      boolean unresolved;
      if (reference instanceof PsiPolyVariantReference) {
        final PsiPolyVariantReference poly = (PsiPolyVariantReference)reference;
        final ResolveResult[] resolveResults = poly.multiResolve(false);
        unresolved = (resolveResults.length == 0);
        for (ResolveResult resolveResult : resolveResults) {
          if (target == null && resolveResult.isValidResult()) {
            target = resolveResult.getElement();
          }
          if (resolveResult instanceof ImportedResolveResult) {
            myUsedImports.addAll(((ImportedResolveResult)resolveResult).getNameDefiners());
          }
        }
      }
      else {
        target = reference.resolve();
        unresolved = (target == null);
      }
      if (unresolved) {
        boolean ignoreUnresolved = false;
        for (PyInspectionExtension extension : Extensions.getExtensions(PyInspectionExtension.EP_NAME)) {
          if (extension.ignoreUnresolvedReference(node, reference)) {
            ignoreUnresolved = true;
            break;
          }
        }
        if (!ignoreUnresolved) {
          registerUnresolvedReferenceProblem(node, reference, severity);
        }
        // don't highlight unresolved imports as unused
        if (node.getParent() instanceof PyImportElement) {
          myAllImports.remove(node.getParent());
        }
      }
      else if (reference instanceof PyImportReference &&
               target == reference.getElement().getContainingFile() &&
               !isContainingFileImportAllowed(node, (PsiFile)target)) {
        registerProblem(node, "Import resolves to its containing file");
      }
    }

    private static boolean isContainingFileImportAllowed(PyElement node, PsiFile target) {
      // import resolving to containing file is allowed when we're importing from the current package and the containing file
      // is __init__.py (PY-5265)
      final boolean insideFromImport = PsiTreeUtil.getParentOfType(node, PyFromImportStatement.class) != null;
      if (!insideFromImport) {
        return false;
      }
      if (PyImportStatementNavigator.getImportStatementByElement(node) != null) {
        return false;
      }
      return target.getName().equals(PyNames.INIT_DOT_PY);
    }

    private void processReferenceInImportGuard(PyElement node, PyExceptPart guard) {
      final PyImportElement importElement = PsiTreeUtil.getParentOfType(node, PyImportElement.class);
      if (importElement != null) {
        final String visibleName = importElement.getVisibleName();
        final ScopeOwner owner = ScopeUtil.getScopeOwner(importElement);
        if (visibleName != null && owner != null) {
          final Collection<PsiElement> allWrites = ScopeUtil.getReadWriteElements(visibleName, owner, false, true);
          final Collection<PsiElement> writesInsideGuard = new ArrayList<PsiElement>();
          for (PsiElement write : allWrites) {
            if (PsiTreeUtil.isAncestor(guard, write, false)) {
              writesInsideGuard.add(write);
            }
          }
          if (writesInsideGuard.isEmpty()) {
            final PyTargetExpression asElement = importElement.getAsNameElement();
            final PyElement toHighlight = asElement != null ? asElement : node;
            registerProblem(toHighlight,
                            PyBundle.message("INSP.try.except.import.error",
                                             visibleName),
                            ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
          }
        }
      }
    }

    private void registerUnresolvedReferenceProblem(@NotNull PyElement node, @NotNull final PsiReference reference,
                                                    @NotNull HighlightSeverity severity) {
      if (reference instanceof DocStringTypeReference) {
        return;
      }
      String description = null;
      final String text = reference.getElement().getText();
      TextRange rangeInElement = reference.getRangeInElement();
      String ref_text = text;  // text of the part we're working with
      if (rangeInElement.getStartOffset() > 0 && rangeInElement.getEndOffset() > 0) {
        ref_text = rangeInElement.substring(text);
      }
      final PsiElement element = reference.getElement();
      final List<LocalQuickFix> actions = new ArrayList<LocalQuickFix>(2);
      final String refname = (element instanceof PyQualifiedExpression) ? ((PyQualifiedExpression)element).getReferencedName() : ref_text;
      // Empty text, nothing to highlight
      if (refname == null || refname.length() <= 0) {
        return;
      }

      final QualifiedName canonicalQName = getCanonicalName(reference, myTypeEvalContext);
      final String canonicalName = canonicalQName != null ? canonicalQName.toString() : null;
      if (canonicalName != null) {
        for (String ignored : myIgnoredIdentifiers) {
          if (ignored.endsWith(END_WILDCARD)) {
            final String prefix = ignored.substring(0, ignored.length() - END_WILDCARD.length());
            if (canonicalName.startsWith(prefix)) {
              return;
            }
          }
          else if (canonicalName.equals(ignored)) {
            return;
          }
        }
      }
      // Legacy non-qualified ignore patterns
      if (myIgnoredIdentifiers.contains(refname)) {
        return;
      }

      if (element instanceof PyReferenceExpression) {
        PyReferenceExpression refex = (PyReferenceExpression)element;
        if (PyNames.COMPARISON_OPERATORS.contains(refname)) {
          return;
        }
        if (refex.getQualifier() != null) {
          final PyClassTypeImpl object_type = (PyClassTypeImpl)PyBuiltinCache.getInstance(node).getObjectType();
          if ((object_type != null) && object_type.getPossibleInstanceMembers().contains(refname)) return;
        }
        else {
          if (PyUnreachableCodeInspection.hasAnyInterruptedControlFlowPaths(refex)) {
            return;
          }
          if (LanguageLevel.forElement(node).isOlderThan(LanguageLevel.PYTHON26)) {
            if ("with".equals(refname)) {
              actions.add(new UnresolvedRefAddFutureImportQuickFix());
            }
          }
          if (ref_text.equals("true") || ref_text.equals("false")) {
            actions.add(new UnresolvedRefTrueFalseQuickFix(element));
          }
          addAddSelfFix(node, refex, actions);
          PyCallExpression callExpression = PsiTreeUtil.getParentOfType(element, PyCallExpression.class);
          if (callExpression != null) {
            actions.add(new UnresolvedRefCreateFunctionQuickFix(callExpression, refex));
          }
          PyFunction parentFunction = PsiTreeUtil.getParentOfType(element, PyFunction.class);
          PyDecorator decorator = PsiTreeUtil.getParentOfType(element, PyDecorator.class);
          PyImportStatement importStatement = PsiTreeUtil.getParentOfType(element, PyImportStatement.class);
          if (parentFunction != null && decorator == null && importStatement == null) {
            actions.add(new UnresolvedReferenceAddParameterQuickFix(refname));
          }
          actions.add(new PyRenameUnresolvedRefQuickFix());
        }
        // unqualified:
        // may be module's
        if (PyModuleType.getPossibleInstanceMembers().contains(refname)) return;
        // may be a "try: import ..."; not an error not to resolve
        if ((
          PsiTreeUtil.getParentOfType(
            PsiTreeUtil.getParentOfType(node, PyImportElement.class), PyTryExceptStatement.class, PyIfStatement.class
          ) != null
        )) {
          severity = HighlightSeverity.WEAK_WARNING;
          description = PyBundle.message("INSP.module.$0.not.found", ref_text);
          // TODO: mark the node so that future references pointing to it won't result in a error, but in a warning
        }
      }
      if (reference instanceof PsiReferenceEx && description == null) {
        description = ((PsiReferenceEx)reference).getUnresolvedDescription();
      }
      if (description == null) {
        boolean marked_qualified = false;
        if (element instanceof PyQualifiedExpression) {
          // TODO: Add __qualname__ for Python 3.3 to the skeleton of <class 'object'>, introduce a pseudo-class skeleton for
          // <class 'function'>
          if ("__qualname__".equals(ref_text) && LanguageLevel.forElement(element).isAtLeast(LanguageLevel.PYTHON33)) {
            return;
          }
          final PyQualifiedExpression qexpr = (PyQualifiedExpression)element;
          if (PyNames.COMPARISON_OPERATORS.contains(qexpr.getReferencedName()) || refname == null) {
            return;
          }
          final PyExpression qualifier = qexpr.getQualifier();
          if (qualifier != null) {
            PyType qtype = myTypeEvalContext.getType(qualifier);
            if (qtype != null) {
              if (ignoreUnresolvedMemberForType(qtype, reference, refname)) {
                return;
              }
              addCreateMemberFromUsageFixes(qtype, reference, ref_text, actions);
              if (qtype instanceof PyClassTypeImpl) {
                if (reference instanceof PyOperatorReference) {
                  description = PyBundle.message("INSP.unresolved.operator.ref",
                                                 qtype.getName(), refname,
                                                 ((PyOperatorReference)reference).getReadableOperatorName());
                }
                else {
                  description = PyBundle.message("INSP.unresolved.ref.$0.for.class.$1", ref_text, qtype.getName());
                }
                marked_qualified = true;
              }
              else {
                description = PyBundle.message("INSP.cannot.find.$0.in.$1", ref_text, qtype.getName());
                marked_qualified = true;
              }
            }
          }
        }
        if (!marked_qualified) {
          description = PyBundle.message("INSP.unresolved.ref.$0", ref_text);

          // look in other imported modules for this whole name
          if (PythonReferenceImporter.isImportable(element)) {
            addAutoImportFix(node, reference, actions);
          }

          addCreateClassFix(ref_text, element, actions);
        }
      }
      ProblemHighlightType hl_type;
      if (severity == HighlightSeverity.WARNING) {
        hl_type = ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
      }
      else if (severity == HighlightSeverity.ERROR) {
        hl_type = ProblemHighlightType.GENERIC_ERROR;
      }
      else {
        hl_type = ProblemHighlightType.LIKE_UNKNOWN_SYMBOL;
      }

      if (GenerateBinaryStubsFix.isApplicable(reference)) {
        actions.add(new GenerateBinaryStubsFix(reference));
      }
      if (canonicalQName != null) {
        actions.add(new AddIgnoredIdentifierQuickFix(canonicalQName, false));
        if (canonicalQName.getComponentCount() > 1) {
          actions.add(new AddIgnoredIdentifierQuickFix(canonicalQName.removeLastComponent(), true));
        }
      }
      addPluginQuickFixes(reference, actions);

      final PsiElement point;
      final TextRange range;
      if (reference instanceof PyOperatorReference) {
        point = node;
        range = rangeInElement;
      }
      else {
        final PsiElement lastChild = node.getLastChild();
        point = lastChild != null ? lastChild : node; // usually the identifier at the end of qual ref
        range = rangeInElement.shiftRight(-point.getStartOffsetInParent());
      }
      if (reference instanceof PyImportReference && refname != null) {
        // TODO: Ignore references in the second part of the 'from ... import ...' expression
        final QualifiedName qname = QualifiedName.fromDottedString(refname);
        final List<String> components = qname.getComponents();
        if (!components.isEmpty()) {
          final String packageName = components.get(0);
          final Module module = ModuleUtilCore.findModuleForPsiElement(node);
          final Sdk sdk = PythonSdkType.findPythonSdk(module);
          if (module != null && sdk != null) {
            if (PyPIPackageUtil.INSTANCE.isInPyPI(packageName)) {
              final List<PyRequirement> requirements = Collections.singletonList(new PyRequirement(packageName));
              final String name = "Install package " + packageName;
              if (PyPackageManager.getInstance(sdk).hasPip()) {
                actions.add(new PyPackageRequirementsInspection.PyInstallRequirementsFix(name, module, sdk, requirements));
              }
            }
          }
        }
      }
      registerProblem(point, description, hl_type, null, range, actions.toArray(new LocalQuickFix[actions.size()]));
    }

    /**
     * Return the canonical qualified name for a reference (even for an unresolved one).
     */
    @Nullable
    private static QualifiedName getCanonicalName(@NotNull PsiReference reference, @NotNull TypeEvalContext context) {
      final PsiElement element = reference.getElement();
      if (reference instanceof PyOperatorReference && element instanceof PyQualifiedExpression) {
        final PyExpression receiver = ((PyOperatorReference)reference).getReceiver();
        if (receiver != null) {
          final PyType type = context.getType(receiver);
          if (type instanceof PyClassType) {
            final String name = ((PyClassType)type).getClassQName();
            if (name != null) {
              return QualifiedName.fromDottedString(name).append(((PyQualifiedExpression)element).getReferencedName());
            }
          }
        }
      }
      else if (element instanceof PyReferenceExpression) {
        final PyReferenceExpression expr = (PyReferenceExpression)element;
        final PyExpression qualifier = expr.getQualifier();
        final String exprName = expr.getName();
        if (exprName != null) {
          if (qualifier != null) {
            final PyType type = context.getType(qualifier);
            if (type instanceof PyClassType) {
              final String name = ((PyClassType)type).getClassQName();
              if (name != null) {
                return QualifiedName.fromDottedString(name).append(exprName);
              }
            }
            else if (type instanceof PyModuleType) {
              final PyFile file = ((PyModuleType)type).getModule();
              final QualifiedName name = QualifiedNameFinder.findCanonicalImportPath(file, element);
              if (name != null) {
                return name.append(exprName);
              }
            }
          }
          else {
            final PsiElement parent = element.getParent();
            if (parent instanceof PyImportElement) {
              final PyImportStatementBase importStmt = PsiTreeUtil.getParentOfType(parent, PyImportStatementBase.class);
              if (importStmt instanceof PyImportStatement) {
                return QualifiedName.fromComponents(exprName);
              }
              else if (importStmt instanceof PyFromImportStatement) {
                final PsiElement resolved = ((PyFromImportStatement)importStmt).resolveImportSource();
                if (resolved != null) {
                  final QualifiedName path = QualifiedNameFinder.findCanonicalImportPath(resolved, element);
                  if (path != null) {
                    return path.append(exprName);
                  }
                }
              }
            }
            else {
              final QualifiedName path = QualifiedNameFinder.findCanonicalImportPath(element, element);
              if (path != null) {
                return path.append(exprName);
              }
            }
          }
        }
      }
      else if (reference instanceof DocStringParameterReference) {
        return QualifiedName.fromDottedString(reference.getCanonicalText());
      }
      return null;
    }

    private boolean ignoreUnresolvedMemberForType(@NotNull PyType qtype, PsiReference reference, String name) {
      if (qtype instanceof PyNoneType || PyTypeChecker.isUnknown(qtype)) {
        // this almost always means that we don't know the type, so don't show an error in this case
        return true;
      }
      if (qtype instanceof PyImportedModuleType) {
        PyImportedModule module = ((PyImportedModuleType)qtype).getImportedModule();
        if (module.resolve() == null) {
          return true;
        }
      }
      if (qtype instanceof PyClassTypeImpl) {
        PyClass cls = ((PyClassType)qtype).getPyClass();
        if (overridesGetAttr(cls, myTypeEvalContext)) {
          return true;
        }
        if (cls.findProperty(name) != null) {
          return true;
        }
        if (PyUtil.hasUnresolvedAncestors(cls, myTypeEvalContext)) {
          return true;
        }
        if (isDecoratedAsDynamic(cls, true)) {
          return true;
        }
        if (hasUnresolvedDynamicMember((PyClassType)qtype, reference, name)) return true;
      }
      if (qtype instanceof PyFunctionType) {
        final Callable callable = ((PyFunctionType)qtype).getCallable();
        if (callable instanceof PyFunction && ((PyFunction)callable).getDecoratorList() != null) {
          return true;
        }
      }
      for (PyInspectionExtension extension : Extensions.getExtensions(PyInspectionExtension.EP_NAME)) {
        if (extension.ignoreUnresolvedMember(qtype, name)) {
          return true;
        }
      }
      return false;
    }

    private static boolean hasUnresolvedDynamicMember(@NotNull final PyClassType qtype,
                                                      PsiReference reference,
                                                      @NotNull final String name) {
      for (PyClassMembersProvider provider : Extensions.getExtensions(PyClassMembersProvider.EP_NAME)) {
        final Collection<PyDynamicMember> resolveResult = provider.getMembers(qtype, reference.getElement());
        for (PyDynamicMember member : resolveResult) {
          if (member.getName().equals(name)) return true;
        }
      }
      return false;
    }

    private boolean isDecoratedAsDynamic(@NotNull PyClass cls, boolean inherited) {
      if (inherited) {
        if (isDecoratedAsDynamic(cls, false)) {
          return true;
        }
        for (PyClass base : cls.getAncestorClasses(myTypeEvalContext)) {
          if (base != null && isDecoratedAsDynamic(base, false)) {
            return true;
          }
        }
      }
      else {
        if (cls.getDecoratorList() != null) {
          return true;
        }
        final String docString = cls.getDocStringValue();
        if (docString != null && docString.contains("@DynamicAttrs")) {
          return true;
        }
      }
      return false;
    }

    private static void addCreateMemberFromUsageFixes(PyType qtype, PsiReference reference, String refText, List<LocalQuickFix> actions) {
      PsiElement element = reference.getElement();
      if (qtype instanceof PyClassTypeImpl) {
        PyClass cls = ((PyClassType)qtype).getPyClass();
        if (!PyBuiltinCache.getInstance(element).hasInBuiltins(cls)) {
          if (element.getParent() instanceof PyCallExpression) {
            actions.add(new AddMethodQuickFix(refText, (PyClassType)qtype, true));
          }
          else if (!(reference instanceof PyOperatorReference)) {
            actions.add(new AddFieldQuickFix(refText, (PyClassType)qtype, "None"));
          }
        }
      }
      else if (qtype instanceof PyModuleType) {
        PyFile file = ((PyModuleType)qtype).getModule();
        actions.add(new AddFunctionQuickFix(refText, file));
      }
    }

    private void addAddSelfFix(PyElement node, PyReferenceExpression refex, List<LocalQuickFix> actions) {
      final PyClass containedClass = PsiTreeUtil.getParentOfType(node, PyClass.class);
      final PyFunction function = PsiTreeUtil.getParentOfType(node, PyFunction.class);
      if (containedClass != null && function != null) {
        final PyParameter[] parameters = function.getParameterList().getParameters();
        if (parameters.length == 0) return;
        final String qualifier = parameters[0].getText();
        final PyDecoratorList decoratorList = function.getDecoratorList();
        boolean isClassmethod = false;
        if (decoratorList != null) {
          for (PyDecorator decorator : decoratorList.getDecorators()) {
            final PyExpression callee = decorator.getCallee();
            if (callee != null && PyNames.CLASSMETHOD.equals(callee.getText()))
              isClassmethod = true;
          }
        }
        for (PyTargetExpression target : containedClass.getInstanceAttributes()) {
          if (!isClassmethod && Comparing.strEqual(node.getName(), target.getName())) {
            actions.add(new UnresolvedReferenceAddSelfQuickFix(refex, qualifier));
          }
        }
        for (PyStatement statement : containedClass.getStatementList().getStatements()) {
          if (statement instanceof PyAssignmentStatement) {
            PyExpression lhsExpression = ((PyAssignmentStatement)statement).getLeftHandSideExpression();
            if (lhsExpression != null && lhsExpression.getText().equals(refex.getText())) {
              PyExpression callexpr = ((PyAssignmentStatement)statement).getAssignedValue();
              if (callexpr instanceof PyCallExpression) {
                PyType type = myTypeEvalContext.getType(callexpr);
                if (type != null && type instanceof PyClassTypeImpl) {
                  if (((PyCallExpression)callexpr).isCalleeText(PyNames.PROPERTY)) {
                    actions.add(new UnresolvedReferenceAddSelfQuickFix(refex, qualifier));
                  }
                }
              }
            }
          }
        }
        for (PyFunction method : containedClass.getMethods()) {
          if (refex.getText().equals(method.getName())) {
            actions.add(new UnresolvedReferenceAddSelfQuickFix(refex, qualifier));
          }
        }
      }
    }

    private static void addAutoImportFix(PyElement node, PsiReference reference, List<LocalQuickFix> actions) {
      final PsiFile file = InjectedLanguageManager.getInstance(node.getProject()).getTopLevelFile(node);
      if (!(file instanceof PyFile)) return;
      AutoImportQuickFix importFix = PythonReferenceImporter.proposeImportFix(node, reference);
      if (importFix != null) {
        if (!suppressHintForAutoImport(node, importFix) && PyCodeInsightSettings.getInstance().SHOW_IMPORT_POPUP) {
          final AutoImportHintAction autoImportHintAction = new AutoImportHintAction(importFix);
          actions.add(autoImportHintAction);
        }
        else {
          actions.add(importFix);
        }
      }
    }

    private static boolean suppressHintForAutoImport(PyElement node, AutoImportQuickFix importFix) {
      // if the context doesn't look like a function call and we only found imports of functions, suggest auto-import
      // as a quickfix but no popup balloon (PY-2312)
      if (!isCall(node) && importFix.hasOnlyFunctions()) {
        return true;
      }
      // if we're in a class context and the class defines a variable with the same name, offer auto-import only as quickfix,
      // not as popup
      PyClass containingClass = PsiTreeUtil.getParentOfType(node, PyClass.class);
      if (containingClass != null && (containingClass.findMethodByName(importFix.getNameToImport(), true) != null ||
                                      containingClass.findInstanceAttribute(importFix.getNameToImport(), true) != null)) {
        return true;
      }
      return false;
    }

    private void addCreateClassFix(String refText, PsiElement element, List<LocalQuickFix> actions) {
      if (refText.length() > 2 && Character.isUpperCase(refText.charAt(0)) && !refText.toUpperCase().equals(refText) &&
          PsiTreeUtil.getParentOfType(element, PyImportStatementBase.class) == null) {
        PsiElement anchor = element;
        if (element instanceof PyQualifiedExpression) {
          final PyExpression qexpr = ((PyQualifiedExpression)element).getQualifier();
          if (qexpr != null) {
            final PyType type = myTypeEvalContext.getType(qexpr);
            if (type instanceof PyModuleType) {
              anchor = ((PyModuleType)type).getModule();
            }
            else {
              anchor = null;
            }
          }
          if (anchor != null) {
            actions.add(new CreateClassQuickFix(refText, anchor));
          }
        }
      }
    }

    private static boolean isCall(PyElement node) {
      final PyCallExpression callExpression = PsiTreeUtil.getParentOfType(node, PyCallExpression.class);
      return callExpression != null && node == callExpression.getCallee();
    }

    @Nullable
    private static PsiElement resolveClassMember(@NotNull PyClass cls, @NotNull String name, @NotNull TypeEvalContext context) {
      final PyType type = context.getType(cls);
      if (type != null) {
        final PyResolveContext resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(context);
        final List<? extends RatedResolveResult> results = type.resolveMember(name, null, AccessDirection.READ, resolveContext);
        if (results != null && !results.isEmpty()) {
          return results.get(0).getElement();
        }
      }
      return null;
    }

    private static boolean overridesGetAttr(@NotNull PyClass cls, @NotNull TypeEvalContext context) {
      PsiElement method = resolveClassMember(cls, PyNames.GETATTR, context);
      if (method != null) {
        return true;
      }
      method = resolveClassMember(cls, PyNames.GETATTRIBUTE, context);
      if (method != null && !PyBuiltinCache.getInstance(cls).hasInBuiltins(method)) {
        return true;
      }
      return false;
    }

    private static void addPluginQuickFixes(PsiReference reference, final List<LocalQuickFix> actions) {
      for (PyUnresolvedReferenceQuickFixProvider provider : Extensions.getExtensions(PyUnresolvedReferenceQuickFixProvider.EP_NAME)) {
        provider.registerQuickFixes(reference, new Consumer<LocalQuickFix>() {
          public void consume(LocalQuickFix localQuickFix) {
            actions.add(localQuickFix);
          }
        });
      }
    }

    public void highlightUnusedImports() {
      final List<PsiElement> unused = collectUnusedImportElements();
      for (PsiElement element : unused) {
        if (element.getTextLength() > 0) {
          registerProblem(element, "Unused import statement", ProblemHighlightType.LIKE_UNUSED_SYMBOL, null, new OptimizeImportsQuickFix());
        }
      }
    }

    private List<PsiElement> collectUnusedImportElements() {
      if (myAllImports.isEmpty()) {
        return Collections.emptyList();
      }
      // PY-1315 Unused imports inspection shouldn't work in python repl console
      final NameDefiner first = myAllImports.iterator().next();
      if (first.getContainingFile() instanceof PyExpressionCodeFragment || PydevConsoleRunner.isInPydevConsole(first)) {
        return Collections.emptyList();
      }
      List<PsiElement> result = new ArrayList<PsiElement>();

      Set<NameDefiner> unusedImports = new HashSet<NameDefiner>(myAllImports);
      unusedImports.removeAll(myUsedImports);
      Set<String> usedImportNames = new HashSet<String>();
      for (PsiElement usedImport : myUsedImports) {
        if (usedImport instanceof NameDefiner) {
          for (PyElement e : ((NameDefiner)usedImport).iterateNames()) {
            usedImportNames.add(e.getName());
          }
        }
      }

      Set<PyImportStatementBase> unusedStatements = new HashSet<PyImportStatementBase>();
      final PyUnresolvedReferencesInspection suppressableInspection = new PyUnresolvedReferencesInspection();
      QualifiedName packageQName = null;
      List<String> dunderAll = null;

          for (NameDefiner unusedImport : unusedImports) {
        if (packageQName == null) {
          final PsiFile file = unusedImport.getContainingFile();
          if (file instanceof PyFile) {
            dunderAll = ((PyFile)file).getDunderAll();
          }
          if (file != null && PyUtil.isPackage(file)) {
            packageQName = QualifiedNameFinder.findShortestImportableQName(file);
          }
        }
        PyImportStatementBase importStatement = PsiTreeUtil.getParentOfType(unusedImport, PyImportStatementBase.class);
        if (importStatement != null && !unusedStatements.contains(importStatement) && !myUsedImports.contains(importStatement)) {
          if (suppressableInspection.isSuppressedFor(importStatement)) {
            continue;
          }
          // don't remove as unused imports in try/except statements
          if (PsiTreeUtil.getParentOfType(importStatement, PyTryExceptStatement.class) != null) {
            continue;
          }
          // Don't report conditional imports as unused
          if (PsiTreeUtil.getParentOfType(unusedImport, PyIfStatement.class) != null) {
            boolean isUsed = false;
            for (PyElement e : unusedImport.iterateNames()) {
              if (usedImportNames.contains(e.getName())) {
                isUsed = true;
              }
            }
            if (isUsed) {
              continue;
            }
          }
          PsiElement importedElement;
          if (unusedImport instanceof PyImportElement) {
            final PyImportElement importElement = (PyImportElement)unusedImport;
            final PsiElement element = importElement.resolve();
            if (element == null) {
              continue;
            }
            if (dunderAll != null && dunderAll.contains(importElement.getVisibleName())) {
              continue;
            }
            importedElement = element.getContainingFile();
          }
          else {
            assert importStatement instanceof PyFromImportStatement;
            importedElement = ((PyFromImportStatement)importStatement).resolveImportSource();
            if (importedElement == null) {
              continue;
            }
          }
          if (packageQName != null && importedElement instanceof PsiFileSystemItem) {
            final QualifiedName importedQName = QualifiedNameFinder.findShortestImportableQName((PsiFileSystemItem)importedElement);
            if (importedQName != null && importedQName.matchesPrefix(packageQName)) {
              continue;
            }
          }
          if (unusedImport instanceof PyStarImportElement || areAllImportsUnused(importStatement, unusedImports)) {
            unusedStatements.add(importStatement);
            result.add(importStatement);
          }
          else {
            result.add(unusedImport);
          }
        }
      }
      return result;
    }

    private static boolean areAllImportsUnused(PyImportStatementBase importStatement, Set<NameDefiner> unusedImports) {
      final PyImportElement[] elements = importStatement.getImportElements();
      for (PyImportElement element : elements) {
        if (!unusedImports.contains(element)) {
          return false;
        }
      }
      return true;
    }

    public void optimizeImports() {
      final List<PsiElement> elementsToDelete = collectUnusedImportElements();
      for (PsiElement element : elementsToDelete) {
        if (element.isValid()) {
          element.delete();
        }
      }
    }
  }

}
