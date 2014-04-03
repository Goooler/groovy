/*
 * Copyright 2003-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.transform.trait;

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.ExceptionUtils;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.syntax.Token;

import java.util.Collection;
import java.util.List;

/**
 * This expression transformer is used internally by the {@link org.codehaus.groovy.transform.trait.TraitASTTransformation trait}
 * AST transformation to change the receiver of a message on "this" into a static method call on the trait helper class.
 * <p></p>
 * In a nutshell, code like this one in a trait:<p></p>
 * <code>void foo() { this.bar() }</code>
 * is transformed into:
 * <code>void foo() { TraitHelper$bar(this) }</code>
 *
 * @author Cedric Champeau
 * @since 2.3.0
 */
class TraitReceiverTransformer extends ClassCodeExpressionTransformer {

    private final VariableExpression weaved;
    private final SourceUnit unit;
    private final ClassNode traitClass;
    private final ClassNode fieldHelper;
    private final Collection<String> knownFields;

    public TraitReceiverTransformer(VariableExpression thisObject, SourceUnit unit, final ClassNode traitClass, ClassNode fieldHelper, Collection<String> knownFields) {
        this.weaved = thisObject;
        this.unit = unit;
        this.traitClass = traitClass;
        this.fieldHelper = fieldHelper;
        this.knownFields = knownFields;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return unit;
    }

    @Override
    public Expression transform(final Expression exp) {
        if (exp instanceof BinaryExpression) {
            Expression leftExpression = ((BinaryExpression) exp).getLeftExpression();
            Expression rightExpression = ((BinaryExpression) exp).getRightExpression();
            Token operation = ((BinaryExpression) exp).getOperation();
            if (operation.getText().equals("=")) {
                String leftFieldName = null;
                // it's an assignment
                if (leftExpression instanceof VariableExpression && ((VariableExpression) leftExpression).getAccessedVariable() instanceof FieldNode) {
                    leftFieldName = ((VariableExpression) leftExpression).getAccessedVariable().getName();
                } else if (leftExpression instanceof FieldExpression) {
                    leftFieldName = ((FieldExpression) leftExpression).getFieldName();
                } else if (leftExpression instanceof PropertyExpression
                        && (((PropertyExpression) leftExpression).isImplicitThis() || "this".equals(((PropertyExpression) leftExpression).getObjectExpression().getText()))) {
                    leftFieldName = ((PropertyExpression) leftExpression).getPropertyAsString();
                }
                if (leftFieldName!=null) {
                    String method = Traits.helperSetterName(new FieldNode(leftFieldName, 0, ClassHelper.OBJECT_TYPE, weaved.getOriginType(), null));
                    MethodCallExpression mce = new MethodCallExpression(
                            new CastExpression(fieldHelper,weaved),
                            method,
                            new ArgumentListExpression(super.transform(rightExpression))
                    );
                    mce.setSourcePosition(exp);
                    mce.setImplicitThis(false);
                    return mce;
                }
            }
            Expression leftTransform = transform(leftExpression);
            Expression rightTransform = transform(rightExpression);
            Expression ret =
                    exp instanceof DeclarationExpression ?new DeclarationExpression(
                            leftTransform, operation, rightTransform
                    ):
                    new BinaryExpression(leftTransform, operation, rightTransform);
            ret.setSourcePosition(exp);
            ret.copyNodeMetaData(exp);
            return ret;
        } else if (exp instanceof MethodCallExpression) {
            MethodCallExpression call = (MethodCallExpression) exp;
            Expression obj = call.getObjectExpression();
            if (call.isImplicitThis() || obj.getText().equals("this")) {
                Expression method = call.getMethod();
                Expression arguments = call.getArguments();
                if (method instanceof ConstantExpression) {
                    String methodName = method.getText();
                    List<MethodNode> methods = traitClass.getMethods(methodName);
                    for (MethodNode methodNode : methods) {
                        if (methodName.equals(methodNode.getName()) && methodNode.isPrivate()) {
                            ArgumentListExpression newArgs = new ArgumentListExpression();
                            newArgs.addExpression(new VariableExpression(weaved));
                            if (arguments instanceof ArgumentListExpression) {
                                List<Expression> expressions = ((ArgumentListExpression) arguments).getExpressions();
                                for (Expression expression : expressions) {
                                    newArgs.addExpression(expression);
                                }
                            } else {
                                newArgs.addExpression(arguments);
                            }
                            MethodCallExpression transformed = new MethodCallExpression(
                                    new VariableExpression("this"),
                                    methodName,
                                    newArgs
                            );
                            transformed.setSourcePosition(call);
                            transformed.setSafe(call.isSafe());
                            transformed.setSpreadSafe(call.isSpreadSafe());
                            transformed.setImplicitThis(true);
                            return transformed;
                        }
                    }
                }

                MethodCallExpression transformed = new MethodCallExpression(
                        weaved,
                        method,
                        transform(arguments)
                );
                transformed.setSourcePosition(call);
                transformed.setSafe(call.isSafe());
                transformed.setSpreadSafe(call.isSpreadSafe());
                transformed.setImplicitThis(false);
                return transformed;
            }
        } else if (exp instanceof FieldExpression) {
            MethodCallExpression mce = new MethodCallExpression(
                    new CastExpression(fieldHelper,weaved),
                    Traits.helperGetterName(((FieldExpression) exp).getField()),
                    ArgumentListExpression.EMPTY_ARGUMENTS
            );
            mce.setSourcePosition(exp);
            mce.setImplicitThis(false);
            return mce;
        } else if (exp instanceof VariableExpression) {
            VariableExpression vexp = (VariableExpression) exp;
            Variable accessedVariable = vexp.getAccessedVariable();
            if (accessedVariable instanceof FieldNode) {
                MethodCallExpression mce = new MethodCallExpression(
                        new CastExpression(fieldHelper,weaved),
                        Traits.helperGetterName((FieldNode) accessedVariable),
                        ArgumentListExpression.EMPTY_ARGUMENTS
                );
                mce.setSourcePosition(exp);
                mce.setImplicitThis(false);
                return mce;
            } else if (accessedVariable instanceof PropertyNode) {
                String propName = accessedVariable.getName();
                if (knownFields.contains(propName)) {
                    String method = Traits.helperGetterName(new FieldNode(propName, 0, ClassHelper.OBJECT_TYPE, weaved.getOriginType(), null));
                    MethodCallExpression mce = new MethodCallExpression(
                            new CastExpression(fieldHelper,weaved),
                            method,
                            ArgumentListExpression.EMPTY_ARGUMENTS
                    );
                    mce.setSourcePosition(exp);
                    mce.setImplicitThis(false);
                    return mce;
                } else {
                    return new PropertyExpression(
                            new VariableExpression(weaved),
                            accessedVariable.getName()
                    );
                }
            } else if (accessedVariable instanceof DynamicVariable) {
                return new PropertyExpression(
                        new VariableExpression(weaved),
                        accessedVariable.getName()
                );
            }
            if (vexp.isThisExpression()) {
                VariableExpression res = new VariableExpression(weaved);
                res.setSourcePosition(exp);
                return res;
            }
            if (vexp.isSuperExpression()) {
                ExceptionUtils.sneakyThrow(
                        new SyntaxException("Call to super is not allowed in a trait", vexp.getLineNumber(), vexp.getColumnNumber()));
            }
        } else if (exp instanceof PropertyExpression) {
            PropertyExpression pexp = (PropertyExpression) exp;
            Expression object = pexp.getObjectExpression();
            if (pexp.isImplicitThis() || "this".equals(object.getText())) {
                String propName = pexp.getPropertyAsString();
                if (knownFields.contains(propName)) {
                    String method = Traits.helperGetterName(new FieldNode(propName, 0, ClassHelper.OBJECT_TYPE, weaved.getOriginType(), null));
                    MethodCallExpression mce = new MethodCallExpression(
                            new CastExpression(fieldHelper,weaved),
                            method,
                            ArgumentListExpression.EMPTY_ARGUMENTS
                    );
                    mce.setSourcePosition(exp);
                    mce.setImplicitThis(false);
                    return mce;
                }
            }
        } else if (exp instanceof ClosureExpression) {
            MethodCallExpression mce = new MethodCallExpression(
                    exp,
                    "rehydrate",
                    new ArgumentListExpression(
                            new VariableExpression(weaved),
                            new VariableExpression(weaved),
                            new VariableExpression(weaved)
                    )
            );
            mce.setImplicitThis(false);
            mce.setSourcePosition(exp);
            return mce;
        }

        // todo: unary expressions (field++, field+=, ...)
        return super.transform(exp);
    }
}
