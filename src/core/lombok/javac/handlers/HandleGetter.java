/*
 * Copyright (C) 2009-2022 The Project Lombok Authors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.Javac.*;
import static lombok.javac.JavacTreeMaker.TypeTag.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;

import java.util.*;

import lombok.AccessLevel;
import lombok.ConfigurationKeys;
import lombok.core.ClassLiteral;
import lombok.experimental.Accessors;
import lombok.experimental.Delegate;
import lombok.Getter;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.delombok.LombokOptionsFactory;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.JavacTreeMaker.TypeTag;
import lombok.spi.Provides;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCExpressionStatement;
import com.sun.tools.javac.tree.JCTree.JCIf;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCSynchronized;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

/**
 * Handles the {@code lombok.Getter} annotation for javac.
 */
@Provides
public class HandleGetter extends JavacAnnotationHandler<Getter> {
    private static final String GETTER_NODE_NOT_SUPPORTED_ERR = "@Getter is only supported on a class, an enum, or a field.";

    public void generateGetterForType(JavacNode typeNode, JavacNode errorNode, AccessLevel level, boolean checkForTypeLevelGetter, List<JCAnnotation> onMethod) {
        generateGetterForType(typeNode, errorNode, level, false, checkForTypeLevelGetter, onMethod);
    }

    public void generateGetterForType(JavacNode typeNode, JavacNode errorNode, AccessLevel level, boolean map, boolean checkForTypeLevelGetter, List<JCAnnotation> onMethod) {
        if (checkForTypeLevelGetter) {
            if (hasAnnotation(Getter.class, typeNode)) {
                //The annotation will make it happen, so we can skip it.
                return;
            }
        }

        if (!isClassOrEnum(typeNode)) {
            errorNode.addError(GETTER_NODE_NOT_SUPPORTED_ERR);
            return;
        }

        for (JavacNode field : typeNode.down()) {
            if (fieldQualifiesForGetterGeneration(field))
                generateGetterForField(field, errorNode.get(), level, false, map, onMethod);
        }
    }

    public static boolean fieldQualifiesForGetterGeneration(JavacNode field) {
        if (field.getKind() != Kind.FIELD) return false;
        JCVariableDecl fieldDecl = (JCVariableDecl) field.get();
        //Skip fields that start with $
        if (fieldDecl.name.toString().startsWith("$")) return false;
        //Skip static fields.
        if ((fieldDecl.mods.flags & Flags.STATIC) != 0) return false;
        return true;
    }

    /**
     * Generates a getter on the stated field.
     * <p>
     * Used by {@link HandleData}.
     * <p>
     * The difference between this call and the handle method is as follows:
     * <p>
     * If there is a {@code lombok.Getter} annotation on the field, it is used and the
     * same rules apply (e.g. warning if the method already exists, stated access level applies).
     * If not, the getter is still generated if it isn't already there, though there will not
     * be a warning if its already there. The default access level is used.
     *
     * @param fieldNode The node representing the field you want a getter for.
     * @param pos       The node responsible for generating the getter (the {@code @Data} or {@code @Getter} annotation).
     */
    public void generateGetterForField(JavacNode fieldNode, DiagnosticPosition pos, AccessLevel level, boolean lazy, List<JCAnnotation> onMethod) {
        generateGetterForField(fieldNode, pos, level, lazy, false, onMethod);
    }

    public void generateGetterForField(JavacNode fieldNode, DiagnosticPosition pos, AccessLevel level, boolean lazy, boolean map, List<JCAnnotation> onMethod) {
        if (hasAnnotation(Getter.class, fieldNode)) {
            //The annotation will make it happen, so we can skip it.
            return;
        }
        createGetterForField(level, fieldNode, fieldNode, false, lazy, map, onMethod);
    }

    @Override
    public void handle(AnnotationValues<Getter> annotation, JCAnnotation ast, JavacNode annotationNode) {
        handleFlagUsage(annotationNode, ConfigurationKeys.GETTER_FLAG_USAGE, "@Getter");

        Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
        deleteAnnotationIfNeccessary(annotationNode, Getter.class);
        deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
        JavacNode node = annotationNode.up();
        Getter annotationInstance = annotation.getInstance();
        AccessLevel level = annotationInstance.value();
        boolean lazy = annotationInstance.lazy();
        boolean map = annotationInstance.map();
        if (lazy) handleFlagUsage(annotationNode, ConfigurationKeys.GETTER_LAZY_FLAG_USAGE, "@Getter(lazy=true)");

        if (level == AccessLevel.NONE) {
            if (lazy) annotationNode.addWarning("'lazy' does not work with AccessLevel.NONE.");
            return;
        }

        if (node == null) return;

        List<JCAnnotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@Getter(onMethod", annotationNode);

        switch (node.getKind()) {
            case FIELD:
                createGetterForFields(level, fields, annotationNode, true, lazy, map, onMethod);
                break;
            case TYPE:
                if (lazy) annotationNode.addError("'lazy' is not supported for @Getter on a type.");
                generateGetterForType(node, annotationNode, level, map, false, onMethod);
                break;
        }
    }

    public void createGetterForFields(AccessLevel level, Collection<JavacNode> fieldNodes, JavacNode errorNode, boolean whineIfExists, boolean lazy, boolean map, List<JCAnnotation> onMethod) {
        for (JavacNode fieldNode : fieldNodes) {
            createGetterForField(level, fieldNode, errorNode, whineIfExists, lazy, map, onMethod);
        }
    }

    public void createGetterForField(AccessLevel level,
                                     JavacNode fieldNode, JavacNode source, boolean whineIfExists, boolean lazy, List<JCAnnotation> onMethod) {
        createGetterForField(level, fieldNode, source, whineIfExists, lazy, false, onMethod);
    }

    public void createGetterForField(AccessLevel level,
                                     JavacNode fieldNode, JavacNode source, boolean whineIfExists, boolean lazy, boolean map, List<JCAnnotation> onMethod) {

        if (fieldNode.getKind() != Kind.FIELD) {
            source.addError(GETTER_NODE_NOT_SUPPORTED_ERR);
            return;
        }

        JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();

        if (lazy) {
            if ((fieldDecl.mods.flags & Flags.PRIVATE) == 0 || (fieldDecl.mods.flags & Flags.FINAL) == 0) {
                source.addError("'lazy' requires the field to be private and final.");
                return;
            }
            if ((fieldDecl.mods.flags & Flags.TRANSIENT) != 0) {
                source.addError("'lazy' is not supported on transient fields.");
                return;
            }
            if (fieldDecl.init == null) {
                source.addError("'lazy' requires field initialization.");
                return;
            }
        }

        AnnotationValues<Accessors> accessors = getAccessorsForField(fieldNode);
        String methodName = toGetterName(fieldNode, accessors);

        if (methodName == null) {
            source.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
            return;
        }

        for (String altName : toAllGetterNames(fieldNode, accessors)) {
            switch (methodExists(altName, fieldNode, false, 0)) {
                case EXISTS_BY_LOMBOK:
                    return;
                case EXISTS_BY_USER:
                    if (whineIfExists) {
                        String altNameExpl = "";
                        if (!altName.equals(methodName)) altNameExpl = String.format(" (%s)", altName);
                        source.addWarning(
                                String.format("Not generating %s(): A method with that name already exists%s", methodName, altNameExpl));
                    }
                    return;
                default:
                case NOT_EXISTS:
                    //continue scanning the other alt names.
            }
        }

        long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);

        injectMethod(fieldNode.up(), createGetter(access, fieldNode, fieldNode.getTreeMaker(), source, lazy, map, onMethod));
    }

    public JCMethodDecl createGetter(long access, JavacNode field, JavacTreeMaker treeMaker, JavacNode source, boolean lazy, List<JCAnnotation> onMethod) {
        return createGetter(access, field, treeMaker, source, lazy, false, onMethod);
    }

    public JCMethodDecl createGetter(long access, JavacNode field, JavacTreeMaker treeMaker, JavacNode source, boolean lazy, boolean map, List<JCAnnotation> onMethod) {
        JCVariableDecl fieldNode = (JCVariableDecl) field.get();

        // Remember the type; lazy will change it
        JCExpression methodType = cloneType(treeMaker, copyType(treeMaker, fieldNode), source);
        AnnotationValues<Accessors> accessors = JavacHandlerUtil.getAccessorsForField(field);
        // Generate the methodName; lazy will change the field type
        Name methodName = field.toName(toGetterName(field, accessors));
        boolean makeFinal = shouldMakeFinal(field, accessors);

        List<JCStatement> statements;
        JCTree toClearOfMarkers = null;
        int[] methodArgPos = null;
        boolean addSuppressWarningsUnchecked = false;
        if (lazy && !inNetbeansEditor(field)) {
            toClearOfMarkers = fieldNode.init;
            if (toClearOfMarkers instanceof JCMethodInvocation) {
                List<JCExpression> args = ((JCMethodInvocation) toClearOfMarkers).args;
                methodArgPos = new int[args.length()];
                for (int i = 0; i < methodArgPos.length; i++) {
                    methodArgPos[i] = args.get(i).pos;
                }
            }
            statements = createLazyGetterBody(treeMaker, field, source);
            addSuppressWarningsUnchecked = LombokOptionsFactory.getDelombokOptions(field.getContext()).getFormatPreferences().generateSuppressWarnings();
        } else if (map) {
            statements = createSimpleMapGetterBody(treeMaker, field, source, methodType);
        } else {
            statements = createSimpleGetterBody(treeMaker, field);
        }

        JCBlock methodBody = treeMaker.Block(0, statements);

        List<JCTypeParameter> methodGenericParams = List.nil();
        List<JCVariableDecl> parameters = List.nil();
        List<JCExpression> throwsClauses = List.nil();
        JCExpression annotationMethodDefaultValue = null;

        List<JCAnnotation> copyableAnnotations = findCopyableAnnotations(field);
        List<JCAnnotation> delegates = findDelegatesAndRemoveFromField(field);
        List<JCAnnotation> annsOnMethod = copyAnnotations(onMethod).appendList(copyableAnnotations);
        if (field.isFinal()) {
            if (getCheckerFrameworkVersion(field).generatePure())
                annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genTypeRef(field, CheckerFrameworkVersion.NAME__PURE), List.<JCExpression>nil()));
        } else {
            if (getCheckerFrameworkVersion(field).generateSideEffectFree())
                annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genTypeRef(field, CheckerFrameworkVersion.NAME__SIDE_EFFECT_FREE), List.<JCExpression>nil()));
        }
        if (isFieldDeprecated(field))
            annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));

        if (makeFinal) access |= Flags.FINAL;
        JCMethodDecl decl = recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName, methodType,
                methodGenericParams, parameters, throwsClauses, methodBody, annotationMethodDefaultValue), source);

        if (toClearOfMarkers != null) recursiveSetGeneratedBy(toClearOfMarkers, null);
        if (methodArgPos != null) {
            for (int i = 0; i < methodArgPos.length; i++) {
                ((JCMethodInvocation) toClearOfMarkers).args.get(i).pos = methodArgPos[i];
            }
        }
        decl.mods.annotations = decl.mods.annotations.appendList(delegates);
        if (addSuppressWarningsUnchecked) {
            ListBuffer<JCExpression> suppressions = new ListBuffer<JCExpression>();
            if (!Boolean.FALSE.equals(field.getAst().readConfiguration(ConfigurationKeys.ADD_SUPPRESSWARNINGS_ANNOTATIONS))) {
                suppressions.append(treeMaker.Literal("all"));
            }
            suppressions.append(treeMaker.Literal("unchecked"));
            addAnnotation(decl.mods, field, source, "java.lang.SuppressWarnings", treeMaker.NewArray(null, List.<JCExpression>nil(), suppressions.toList()));
        }

        copyJavadoc(field, decl, CopyJavadoc.GETTER);
        return decl;
    }

    public static List<JCAnnotation> findDelegatesAndRemoveFromField(JavacNode field) {
        JCVariableDecl fieldNode = (JCVariableDecl) field.get();

        List<JCAnnotation> delegates = List.nil();
        for (JCAnnotation annotation : fieldNode.mods.annotations) {
            if (typeMatches(Delegate.class, field, annotation.annotationType)) {
                delegates = delegates.append(annotation);
            }
        }

        if (!delegates.isEmpty()) {
            ListBuffer<JCAnnotation> withoutDelegates = new ListBuffer<JCAnnotation>();
            for (JCAnnotation annotation : fieldNode.mods.annotations) {
                if (!delegates.contains(annotation)) {
                    withoutDelegates.append(annotation);
                }
            }
            fieldNode.mods.annotations = withoutDelegates.toList();
            field.rebuild();
        }
        return delegates;
    }

    private static HashSet builtInType = new HashSet();

    static {
        builtInType.add("BigDecimal");
        builtInType.add("Long");
        builtInType.add("Boolean");
        builtInType.add("String");
        builtInType.add("Integer");
        builtInType.add("Double");
        builtInType.add("Float");
        builtInType.add("Short");
        builtInType.add("Byte");
        builtInType.add("Date");
    }

    public List<JCStatement> createSimpleGetterBody(JavacTreeMaker treeMaker, JavacNode field) {
        return List.<JCStatement>of(treeMaker.Return(createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD)));
    }

    public List<JCStatement> createSimpleMapGetterBody(JavacTreeMaker maker, JavacNode field, JavacNode source, JCExpression methodType) {
        String returnType = methodType.toString();

        if (builtInType.contains(returnType)) {
            JCTree.JCExpression mapPutMethod = JavacHandlerUtil.chainDotsString(source, "get");
            final JCTree.JCLiteral literal = maker.Literal(field.getName());
            field.fieldOrMethodBaseType();
            final JCMethodInvocation apply = maker.Apply(List.<JCExpression>nil(), mapPutMethod, List.<JCExpression>of(literal));
            return List.<JCStatement>of(maker.Return(maker.TypeCast(methodType, apply)));
        } else {
            JCTree.JCExpression mapPutMethod = JavacHandlerUtil.chainDotsString(source, "parseProperty");
            final JCTree.JCLiteral literal = maker.Literal(field.getName());

            final JCMethodInvocation apply = maker.Apply(List.<JCExpression>nil(), mapPutMethod, List.<JCExpression>of(literal));
            return List.<JCStatement>of(maker.Return(maker.TypeCast(methodType, apply)));
        }
//		JCTree.JCExpression mapPutMethod = JavacHandlerUtil.chainDotsString(source, "get");
//		final JCTree.JCLiteral literal = maker.Literal(field.getName());
//		field.fieldOrMethodBaseType();
//		final JCMethodInvocation apply = maker.Apply(List.<JCExpression>nil(), mapPutMethod, List.<JCExpression>of(literal));
//		return List.<JCStatement>of(maker.Return(maker.TypeCast(methodType,apply)));
    }

    private static final String AR = "java.util.concurrent.atomic.AtomicReference";
    private static final List<JCExpression> NIL_EXPRESSION = List.nil();

    public static final java.util.Map<TypeTag, String> TYPE_MAP;

    static {
        Map<TypeTag, String> m = new HashMap<TypeTag, String>();
        m.put(CTC_INT, "Integer");
        m.put(CTC_DOUBLE, "Double");
        m.put(CTC_FLOAT, "Float");
        m.put(CTC_SHORT, "Short");
        m.put(CTC_BYTE, "Byte");
        m.put(CTC_LONG, "Long");
        m.put(CTC_BOOLEAN, "Boolean");
        m.put(CTC_CHAR, "Character");
        TYPE_MAP = Collections.unmodifiableMap(m);
    }

    public List<JCStatement> createLazyGetterBody(JavacTreeMaker maker, JavacNode fieldNode, JavacNode source) {
		/*
		java.lang.Object value = this.fieldName.get();
		if (value == null) {
			synchronized (this.fieldName) {
				value = this.fieldName.get();
				if (value == null) {
					final RawValueType actualValue = INITIALIZER_EXPRESSION;
					[IF PRIMITIVE]
					value = actualValue;
					[ELSE]
					value = actualValue == null ? this.fieldName : actualValue;
					[END IF]
					this.fieldName.set(value);
				}
			}
		}
		[IF PRIMITIVE]
		return (BoxedValueType) value;
		[ELSE]
		return (BoxedValueType) (value == this.fieldName ? null : value);
		[END IF]
		*/

        ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();

        JCVariableDecl field = (JCVariableDecl) fieldNode.get();
        JCExpression copyOfRawFieldType = copyType(maker, field);
        JCExpression copyOfBoxedFieldType = null;
        field.type = null;
        boolean isPrimitive = false;
        if (field.vartype instanceof JCPrimitiveTypeTree) {
            String boxed = TYPE_MAP.get(typeTag(field.vartype));
            if (boxed != null) {
                isPrimitive = true;
                field.vartype = genJavaLangTypeRef(fieldNode, boxed);
                copyOfBoxedFieldType = genJavaLangTypeRef(fieldNode, boxed);
            }
        }
        if (copyOfBoxedFieldType == null) copyOfBoxedFieldType = copyType(maker, field);

        Name valueName = fieldNode.toName("value");
        Name actualValueName = fieldNode.toName("actualValue");

        /* java.lang.Object value = this.fieldName.get();*/
        {
            JCExpression valueVarType = genJavaLangTypeRef(fieldNode, "Object");
            statements.append(maker.VarDef(maker.Modifiers(0L), valueName, valueVarType, callGet(fieldNode, createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD))));
        }

        /* if (value == null) { */
        {
            JCSynchronized synchronizedStatement;
            /* synchronized (this.fieldName) { */
            {
                ListBuffer<JCStatement> synchronizedStatements = new ListBuffer<JCStatement>();
                /* value = this.fieldName.get(); */
                {
                    JCExpressionStatement newAssign = maker.Exec(maker.Assign(maker.Ident(valueName), callGet(fieldNode, createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD))));
                    synchronizedStatements.append(newAssign);
                }

                /* if (value == null) { */
                {
                    ListBuffer<JCStatement> innerIfStatements = new ListBuffer<JCStatement>();
                    /* final RawValueType actualValue = INITIALIZER_EXPRESSION; */
                    {
                        innerIfStatements.append(maker.VarDef(maker.Modifiers(Flags.FINAL), actualValueName, copyOfRawFieldType, field.init));
                    }
                    /* [IF primitive] value = actualValue; */
                    {
                        if (isPrimitive) {
                            JCStatement statement = maker.Exec(maker.Assign(maker.Ident(valueName), maker.Ident(actualValueName)));
                            innerIfStatements.append(statement);
                        }
                    }
                    /* [ELSE] value = actualValue == null ? this.fieldName : actualValue; */
                    {
                        if (!isPrimitive) {
                            JCExpression actualValueIsNull = maker.Binary(CTC_EQUAL, maker.Ident(actualValueName), maker.Literal(CTC_BOT, null));
                            JCExpression thisDotFieldName = createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD);
                            JCExpression ternary = maker.Conditional(actualValueIsNull, thisDotFieldName, maker.Ident(actualValueName));
                            JCStatement statement = maker.Exec(maker.Assign(maker.Ident(valueName), ternary));
                            innerIfStatements.append(statement);
                        }
                    }
                    /* this.fieldName.set(value); */
                    {
                        JCStatement statement = callSet(fieldNode, createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD), maker.Ident(valueName));
                        innerIfStatements.append(statement);
                    }

                    JCBinary isNull = maker.Binary(CTC_EQUAL, maker.Ident(valueName), maker.Literal(CTC_BOT, null));
                    JCIf ifStatement = maker.If(isNull, maker.Block(0, innerIfStatements.toList()), null);
                    synchronizedStatements.append(ifStatement);
                }

                synchronizedStatement = maker.Synchronized(createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD), maker.Block(0, synchronizedStatements.toList()));
            }

            JCBinary isNull = maker.Binary(CTC_EQUAL, maker.Ident(valueName), maker.Literal(CTC_BOT, null));
            JCIf ifStatement = maker.If(isNull, maker.Block(0, List.<JCStatement>of(synchronizedStatement)), null);
            statements.append(ifStatement);
        }
        /* [IF PRIMITIVE] return (BoxedValueType) value; */
        {
            if (isPrimitive) {
                statements.append(maker.Return(maker.TypeCast(copyOfBoxedFieldType, maker.Ident(valueName))));
            }
        }
        /* [ELSE] return (BoxedValueType) (value == this.fieldName ? null : value); */
        {
            if (!isPrimitive) {
                JCExpression valueEqualsSelf = maker.Binary(CTC_EQUAL, maker.Ident(valueName), createFieldAccessor(maker, fieldNode, FieldAccess.ALWAYS_FIELD));
                JCExpression ternary = maker.Conditional(valueEqualsSelf, maker.Literal(CTC_BOT, null), maker.Ident(valueName));
                JCExpression typeCast = maker.TypeCast(copyOfBoxedFieldType, maker.Parens(ternary));
                statements.append(maker.Return(typeCast));
            }
        }

        // update the field type and init last

        /*	private final java.util.concurrent.atomic.AtomicReference<Object> fieldName = new java.util.concurrent.atomic.AtomicReference<Object>(); */
        {
            field.vartype = recursiveSetGeneratedBy(
                    maker.TypeApply(chainDotsString(fieldNode, AR), List.<JCExpression>of(genJavaLangTypeRef(fieldNode, "Object"))), source);
            field.init = recursiveSetGeneratedBy(maker.NewClass(null, NIL_EXPRESSION, copyType(maker, field), NIL_EXPRESSION, null), source);
        }

        return statements.toList();
    }

    public JCMethodInvocation callGet(JavacNode source, JCExpression receiver) {
        JavacTreeMaker maker = source.getTreeMaker();
        return maker.Apply(NIL_EXPRESSION, maker.Select(receiver, source.toName("get")), NIL_EXPRESSION);
    }

    public JCStatement callSet(JavacNode source, JCExpression receiver, JCExpression value) {
        JavacTreeMaker maker = source.getTreeMaker();
        return maker.Exec(maker.Apply(NIL_EXPRESSION, maker.Select(receiver, source.toName("set")), List.<JCExpression>of(value)));
    }

    public JCExpression copyType(JavacTreeMaker treeMaker, JCVariableDecl fieldNode) {
        return fieldNode.type != null ? treeMaker.Type(fieldNode.type) : fieldNode.vartype;
    }
}
