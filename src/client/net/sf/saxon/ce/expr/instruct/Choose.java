package client.net.sf.saxon.ce.expr.instruct;
import client.net.sf.saxon.ce.LogController;
import client.net.sf.saxon.ce.expr.*;
import client.net.sf.saxon.ce.expr.parser.CodeInjector;
import client.net.sf.saxon.ce.functions.BooleanFn;
import client.net.sf.saxon.ce.om.Item;
import client.net.sf.saxon.ce.om.SequenceIterator;
import client.net.sf.saxon.ce.om.StandardNames;
import client.net.sf.saxon.ce.om.StructuredQName;
import client.net.sf.saxon.ce.style.StyleElement;
import client.net.sf.saxon.ce.trace.XSLTTraceListener;
import client.net.sf.saxon.ce.trans.XPathException;
import client.net.sf.saxon.ce.tree.iter.EmptyIterator;
import client.net.sf.saxon.ce.tree.util.FastStringBuffer;
import client.net.sf.saxon.ce.type.ItemType;
import client.net.sf.saxon.ce.type.Type;
import client.net.sf.saxon.ce.type.TypeHierarchy;
import client.net.sf.saxon.ce.value.BooleanValue;
import client.net.sf.saxon.ce.value.Cardinality;
import client.net.sf.saxon.ce.value.SequenceType;

import java.util.ArrayList;
import java.util.Iterator;

import com.google.gwt.logging.client.LogConfiguration;

/**
 * Compiled representation of an xsl:choose or xsl:if element in the stylesheet.
 * Also used for typeswitch in XQuery.
*/

public class Choose extends Instruction {

    // The class implements both xsl:choose and xsl:if. There is a list of boolean
    // expressions (conditions) and a list of corresponding actions: the conditions
    // are evaluated in turn, and when one is found that is true, the corresponding
    // action is evaluated. For xsl:if, there is always one condition and one action.
    // An xsl:otherwise is compiled as if it were xsl:when test="true()". If no
    // condition is satisfied, the instruction returns an empty sequence.

    private Expression[] conditions;
    private Expression[] actions;


    /**
    * Construct an xsl:choose instruction
    * @param conditions the conditions to be tested, in order
    * @param actions the actions to be taken when the corresponding condition is true
    */

    public Choose(Expression[] conditions, Expression[] actions) {
        this.conditions = conditions;
        this.actions = actions;
        if (conditions.length != actions.length) {
            throw new IllegalArgumentException("Choose: unequal length arguments");
        }
        for (int i=0; i<conditions.length; i++) {
            adoptChildExpression(conditions[i]);
            adoptChildExpression(actions[i]);
        }
    }

    /**
     * Make a simple conditional expression (if (condition) then (thenExp) else (elseExp)
     * @param condition the condition to be tested
     * @param thenExp the expression to be evaluated if the condition is true
     * @param elseExp the expression to be evaluated if the condition is false
     * @return the expression
     */

    public static Expression makeConditional(Expression condition, Expression thenExp, Expression elseExp) {
        if (Literal.isEmptySequence(elseExp)) {
            Expression[] conditions = new Expression[] {condition};
            Expression[] actions = new Expression[] {thenExp};
            return new Choose(conditions, actions);
        } else {
            Expression[] conditions = new Expression[] {condition, new Literal(BooleanValue.TRUE)};
            Expression[] actions = new Expression[] {thenExp, elseExp};
            return new Choose(conditions, actions);
        }
    }

    /**
     * Make a simple conditional expression (if (condition) then (thenExp) else ()
     * @param condition the condition to be tested
     * @param thenExp the expression to be evaluated if the condition is true
     * @return the expression
     */

    public static Expression makeConditional(Expression condition, Expression thenExp) {
        Expression[] conditions = new Expression[] {condition};
        Expression[] actions = new Expression[] {thenExp};
        return new Choose(conditions, actions);
    }

    /**
     * Test whether an expression is a single-branch choose, that is, an expression of the form
     * if (condition) then exp else ()
     * @param exp the expression to be tested
     * @return true if the expression is a choose expression and there is only one condition,
     * so that the expression returns () if this condition is false
     */

    public static boolean isSingleBranchChoice(Expression exp) {
        return (exp instanceof Choose && ((Choose)exp).conditions.length == 1);
    }

    /**
     * Get the array of conditions to be tested
     * @return the array of condition expressions
     */

    public Expression[] getConditions() {
        return conditions;
    }

    /**
     * Get the array of actions to be performed
     * @return the array of expressions to be evaluated when the corresponding condition is true
     */

    public Expression[] getActions() {
        return actions;
    }
    
    private String[] conditionTests = null;
    
    public void setConditionTests(String[] conditionTests) {
    	this.conditionTests = conditionTests;
    }

    /**
    * Get the name of this instruction for diagnostic and tracing purposes
    * We assume that if there was
     * only one condition then it was an xsl:if; this is not necessarily so, but
     * it's adequate for tracing purposes.
    */


    public int getInstructionNameCode() {
        return (conditions.length==1 ? StandardNames.XSL_IF : StandardNames.XSL_CHOOSE);
    }

    /**
     * Simplify an expression. This performs any static optimization (by rewriting the expression
     * as a different expression).
     *
     * @exception XPathException if an error is discovered during expression
     *     rewriting
     * @return the simplified expression
     * @param visitor expression visitor object
     */

    public Expression simplify(ExpressionVisitor visitor) throws XPathException {
        for (int i=0; i<conditions.length; i++) {
            conditions[i] = visitor.simplify(conditions[i]);
            try {
                actions[i] = visitor.simplify(actions[i]);
            } catch (XPathException err) {
                // mustn't throw the error unless the branch is actually selected, unless its a type error
                if (err.isTypeError()) {
                    throw err;
                } else {
                    actions[i] = new ErrorExpression(err);
                }
            }
        }
        return this;
    }

    public Expression typeCheck(ExpressionVisitor visitor, ItemType contextItemType) throws XPathException {
        for (int i=0; i<conditions.length; i++) {
            conditions[i] = visitor.typeCheck(conditions[i], contextItemType);
            XPathException err = TypeChecker.ebvError(conditions[i], visitor.getConfiguration().getTypeHierarchy());
            if (err != null) {
                err.setLocator(conditions[i].getSourceLocator());
                throw err;
            }
        }
        for (int i=0; i<actions.length; i++) {
            try {
                actions[i] = visitor.typeCheck(actions[i], contextItemType);
            } catch (XPathException err) {
                // mustn't throw the error unless the branch is actually selected, unless its a static or type error
                if (err.isStaticError()) {
                    throw err;
                } else if (err.isTypeError()) {
                    // if this is an "empty" else branch, don't be draconian about the error handling. It might be
                    // the user knows the otherwise branch isn't needed because one of the when branches will always
                    // be satisfied.
                    if (Literal.isEmptySequence(actions[i])) {
                        actions[i] = new ErrorExpression(err);
                    } else {
                        throw err;
                    }
                } else {
                    actions[i] = new ErrorExpression(err);
                }
            }
        }
        return this;
    }

    /**
     * Determine whether this expression implements its own method for static type checking
     *
     * @return true - this expression has a non-trivial implementation of the staticTypeCheck()
     *         method
     */

    public boolean implementsStaticTypeCheck() {
        return true;
    }

    /**
     * Static type checking for conditional expressions is delegated to the expression itself,
     * and is performed separately on each branch of the conditional, so that dynamic checks are
     * added only on those branches where the check is actually required. This also results in a static
     * type error if any branch is incapable of delivering a value of the required type. One reason
     * for this approach is to avoid doing dynamic type checking on a recursive function call as this
     * prevents tail-call optimization being used.
     * @param req the required type
     * @param backwardsCompatible true if backwards compatibility mode applies
     * @param role the role of the expression in relation to the required type
     * @param visitor an expression visitor
     * @return the expression after type checking (perhaps augmented with dynamic type checking code)
     * @throws XPathException if failures occur, for example if the static type of one branch of the conditional
     * is incompatible with the required type
     */

    public Expression staticTypeCheck(SequenceType req,
                                             boolean backwardsCompatible,
                                             RoleLocator role, ExpressionVisitor visitor)
    throws XPathException {
        for (int i=0; i<actions.length; i++) {
            actions[i] = TypeChecker.staticTypeCheck(actions[i], req, backwardsCompatible, role, visitor);
        }
        // If the last condition isn't true(), then we need to consider the fall-through case, which returns
        // an empty sequence
        if (!Literal.isConstantBoolean(conditions[conditions.length-1], true) &&
                !Cardinality.allowsZero(req.getCardinality())) {
            String cond = (conditions.length == 1 ? "the condition is not" : "none of the conditions is");
            XPathException err = new XPathException(
                    "Conditional expression: If " + cond + " satisfied, an empty sequence will be returned, " +
                            "but this is not allowed as the " + role.getMessage());
            err.setErrorCode(role.getErrorCode());
            err.setIsTypeError(true);
            throw err;
        }
        return this;
    }

    public Expression optimize(ExpressionVisitor visitor, ItemType contextItemType) throws XPathException {
        for (int i=0; i<conditions.length; i++) {
            conditions[i] = visitor.optimize(conditions[i], contextItemType);
            Expression ebv = BooleanFn.rewriteEffectiveBooleanValue(conditions[i], visitor, contextItemType);
            if (ebv != null && ebv != conditions[i]) {
                conditions[i] = ebv;
                adoptChildExpression(ebv);
            }
            if (conditions[i] instanceof Literal &&
                    !(((Literal)conditions[i]).getValue() instanceof BooleanValue)) {
                final boolean b;
                try {
                    b = ((Literal)conditions[i]).getValue().effectiveBooleanValue();
                } catch (XPathException err) {
                    err.setLocator(getSourceLocator());
                    throw err;
                }
                conditions[i] = new Literal(BooleanValue.get(b));
            }
        }
        for (int i=0; i<actions.length; i++) {
            try {
                actions[i] = visitor.optimize(actions[i], contextItemType);
            } catch (XPathException err) {
                // mustn't throw the error unless the branch is actually selected, unless its a type error
                if (err.isTypeError()) {
                    throw err;
                } else {
                    actions[i] = new ErrorExpression(err);
                }
            }
        }
        if (actions.length == 0) {
            return Literal.makeEmptySequence();
        }
        return this;
    }


    /**
     * An implementation of Expression must provide at least one of the methods evaluateItem(), iterate(), or process().
     * This method indicates which of these methods is prefered. For instructions this is the process() method.
     */

    public int getImplementationMethod() {
        int m = Expression.PROCESS_METHOD | Expression.ITERATE_METHOD;
        if (!Cardinality.allowsMany(getCardinality())) {
            m |= Expression.EVALUATE_METHOD;
        }
        return m;
    }

    /**
     * Mark tail-recursive calls on functions. For most expressions, this does nothing.
     *
     * @return 0 if no tail call was found; 1 if a tail call on a different function was found;
     * 2 if a tail recursive call was found and if this call accounts for the whole of the value.
     */

    public int markTailFunctionCalls(StructuredQName qName, int arity) {
        int result = 0;
        for (int i=0; i<actions.length; i++) {
            result = Math.max(result, actions[i].markTailFunctionCalls(qName, arity));
        }
        return result;
    }

    /**
     * Get the item type of the items returned by evaluating this instruction
     * @return the static item type of the instruction
     * @param th Type hierarchy cache
     */

    public ItemType getItemType(TypeHierarchy th) {
        ItemType type = actions[0].getItemType(th);
        for (int i=1; i<actions.length; i++) {
            type = Type.getCommonSuperType(type, actions[i].getItemType(th), th);
        }
        return type;
    }

    /**
     * Compute the cardinality of the sequence returned by evaluating this instruction
     * @return the static cardinality
     */

    public int computeCardinality() {
        int card = 0;
        boolean includesTrue = false;
        for (int i=0; i<actions.length; i++) {
            card = Cardinality.union(card, actions[i].getCardinality());
            if (Literal.isConstantBoolean(conditions[i], true)) {
                includesTrue = true;
            }
        }
        if (!includesTrue) {
            // we may drop off the end and return an empty sequence (typical for xsl:if)
            card = Cardinality.union(card, StaticProperty.ALLOWS_ZERO);
        }
        return card;
    }

    /**
     * Get the static properties of this expression (other than its type). The result is
     * bit-signficant. These properties are used for optimizations. In general, if
     * property bit is set, it is true, but if it is unset, the value is unknown.
     *
     * @return a set of flags indicating static properties of this expression
     */

    public int computeSpecialProperties() {
        // The special properties of a conditional are those which are common to every branch of the conditional
        int props = actions[0].getSpecialProperties();
        for (int i=1; i<actions.length; i++) {
            props &= actions[i].getSpecialProperties();
        }
        return props;
    }

    /**
     * Determine whether this instruction creates new nodes.
     * This implementation returns true if any of the "actions" creates new nodes.
     * (Nodes created by the conditions can't contribute to the result).
     */

    public final boolean createsNewNodes() {
        for (int i=0; i<actions.length; i++) {
            int props = actions[i].getSpecialProperties();
            if ((props & StaticProperty.NON_CREATIVE) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all the XPath expressions associated with this instruction
     * (in XSLT terms, the expression present on attributes of the instruction,
     * as distinct from the child instructions in a sequence construction)
     */

    public Iterator<Expression> iterateSubExpressions() {
        return new Iterator<Expression>() {
            boolean doingConditions = true;
            int index = 0;
            public boolean hasNext() {
                return doingConditions || index<actions.length;
            }

            public Expression next() {
                if (doingConditions) {
                    if (index < conditions.length) {
                        return conditions[index++];
                    } else {
                        doingConditions = false;
                        index = 0;
                        return actions[index++];
                    }
                } else {
                    if (index < actions.length) {
                        return actions[index++];
                    } else {
                        return null;
                    }
                }
            }

            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * Replace one subexpression by a replacement subexpression
     * @param original the original subexpression
     * @param replacement the replacement subexpression
     * @return true if the original subexpression is found
     */

    public boolean replaceSubExpression(Expression original, Expression replacement) {
        boolean found = false;
        for (int i=0; i<conditions.length; i++) {
            if (conditions[i] == original) {
                conditions[i] = replacement;
                found = true;
            }
        }
        for (int i=0; i<actions.length; i++) {
            if (actions[i] == original) {
                actions[i] = replacement;
                found = true;
            }
        }
        return found;
    }

    /**
     * Handle promotion offers, that is, non-local tree rewrites.
     * @param offer The type of rewrite being offered
     * @throws XPathException
     */

    protected void promoteInst(PromotionOffer offer) throws XPathException {
        // xsl:when acts as a guard: expressions inside the when mustn't be evaluated if the when is false,
        // and conditions after the first mustn't be evaluated if a previous condition is true. So we
        // don't pass all promotion offers on
        if (offer.action == PromotionOffer.UNORDERED ||
                offer.action == PromotionOffer.REPLACE_CURRENT) {
            for (int i=0; i<conditions.length; i++) {
                conditions[i] = doPromotion(conditions[i], offer);
            }
            for (int i=0; i<actions.length; i++) {
                actions[i] = doPromotion(actions[i], offer);
            }
        } else {
            // in other cases, only the first xsl:when condition is promoted
            conditions[0]  = doPromotion(conditions[0], offer);
        }
    }


    /**
     * The toString() method for an expression attempts to give a representation of the expression
     * in an XPath-like form, but there is no guarantee that the syntax will actually be true XPath.
     * In the case of XSLT instructions, the toString() method gives an abstracted view of the syntax
     * @return a representation of the expression as a string
     */

    public String toString() {
        FastStringBuffer sb = new FastStringBuffer(FastStringBuffer.SMALL);
        sb.append("if (");
        for (int i=0; i<conditions.length; i++) {
            sb.append(conditions[i].toString());
            sb.append(") then (");
            sb.append(actions[i].toString());
            if (i == conditions.length - 1) {
                sb.append(")");
            } else {
                sb.append(") else if (");
            }
        }
        return sb.toString();
    }

    /**
    * Process this instruction, that is, choose an xsl:when or xsl:otherwise child
    * and process it.
    * @param context the dynamic context of this transformation
    * @throws XPathException if any non-recoverable dynamic error occurs
    * @return a TailCall, if the chosen branch ends with a call of call-template or
    * apply-templates. It is the caller's responsibility to execute such a TailCall.
    * If there is no TailCall, returns null.
    */

    public TailCall processLeavingTail(XPathContext context) throws XPathException {
        for (int i=0; i<conditions.length; i++) {
            final boolean b;
            try {
                b = conditions[i].effectiveBooleanValue(context);
            } catch (XPathException e) {
                e.maybeSetLocation(conditions[i].getSourceLocator());
                throw e;
            }
            if (b) {

            	enterConditionTrace(i);
            	TailCall tail;
                if (actions[i] instanceof TailCallReturner) {
                    tail = ((TailCallReturner)actions[i]).processLeavingTail(context);
                } else {
                    actions[i].process(context);
                    tail = null;
                }
                leaveConditionTrace(i);
                return tail;
            }
        }
        return null;
    }

    /**
     * Evaluate an expression as a single item. This always returns either a single Item or
     * null (denoting the empty sequence). No conversion is done. This method should not be
     * used unless the static type of the expression is a subtype of "item" or "item?": that is,
     * it should not be called if the expression may return a sequence. There is no guarantee that
     * this condition will be detected.
     *
     * @param context The context in which the expression is to be evaluated
     * @exception XPathException if any dynamic error occurs evaluating the
     *     expression
     * @return the node or atomic value that results from evaluating the
     *     expression; or null to indicate that the result is an empty
     *     sequence
     */

    public Item evaluateItem(XPathContext context) throws XPathException {
        for (int i=0; i<conditions.length; i++) {
            final boolean b;
            try {
                b = conditions[i].effectiveBooleanValue(context);
            } catch (XPathException e) {
                e.maybeSetLocation(conditions[i].getSourceLocator());
                throw e;
            }
            if (b) {
            		
            	enterConditionTrace(i);
                Item result = actions[i].evaluateItem(context);
                leaveConditionTrace(i);

                return result;
            }
        }
        return null;
    }
    
    /**
     * Return an Iterator to iterate over the values of a sequence. The value of every
     * expression can be regarded as a sequence, so this method is supported for all
     * expressions. This default implementation relies on the process() method: it
     * "pushes" the results of the instruction to a sequence in memory, and then
     * iterates over this in-memory sequence.
     *
     * In principle instructions should implement a pipelined iterate() method that
     * avoids the overhead of intermediate storage.
     *
     * @exception XPathException if any dynamic error occurs evaluating the
     *     expression
     * @param context supplies the context for evaluation
     * @return a SequenceIterator that can be used to iterate over the result
     *     of the expression
     */

    public SequenceIterator iterate(XPathContext context) throws XPathException {
        for (int i=0; i<conditions.length; i++) {
            final boolean b;
            try {
                b = conditions[i].effectiveBooleanValue(context);
            } catch (XPathException e) {
                e.maybeSetLocation(conditions[i].getSourceLocator());
                throw e;
            }
            SequenceIterator result;
            if (b) {
            	// for XSLIF conditionTests will always be null
            	enterConditionTrace(i);
                result = (actions[i]).iterate(context);
                leaveConditionTrace(i);

                return result;
            }
        }
        return EmptyIterator.getInstance();
    }
    
    private void enterConditionTrace(int i) {
    	if (LogConfiguration.loggingIsEnabled() && LogController.traceIsEnabled()) {
			if(conditionTests != null) {
	      	   XSLTTraceListener xlt = (XSLTTraceListener)LogController.getTraceListener();
	      	   xlt.enterChooseItem(conditionTests[i]);
	 		}
    	}
    }
    
    private void leaveConditionTrace(int i) {
    	if (LogConfiguration.loggingIsEnabled() && LogController.traceIsEnabled()) {
	 	   if(conditionTests != null) {
	      	   XSLTTraceListener xlt = (XSLTTraceListener)LogController.getTraceListener();
	      	   xlt.leaveChooseItem(conditionTests[i]);
		   }
    	}
    }


}

// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. 
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is “Incompatible With Secondary Licenses”, as defined by the Mozilla Public License, v. 2.0.
