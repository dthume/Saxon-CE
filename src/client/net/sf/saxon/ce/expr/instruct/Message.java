package client.net.sf.saxon.ce.expr.instruct;

import client.net.sf.saxon.ce.expr.*;
import client.net.sf.saxon.ce.lib.StandardErrorListener;
import client.net.sf.saxon.ce.om.StandardNames;
import client.net.sf.saxon.ce.om.ValueRepresentation;
import client.net.sf.saxon.ce.trans.XPathException;
import client.net.sf.saxon.ce.type.AnyItemType;
import client.net.sf.saxon.ce.type.ItemType;
import client.net.sf.saxon.ce.type.TypeHierarchy;
import com.google.gwt.user.client.Window;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.logging.Level;


/**
* An xsl:message element in the stylesheet.
*/

public class Message extends Instruction {

    private Expression terminate;
    private Expression select;
    private static Logger logger = Logger.getLogger("Message");

    /**
     * Create an xsl:message instruction
     * @param select the expression that constructs the message (composite of the select attribute
     * and the contained sequence constructor)
     * @param terminate expression that calculates terminate = yes or no.
     */

    public Message(Expression select, Expression terminate) {
        this.terminate = terminate;
        this.select = select;
        adoptChildExpression(terminate);
        adoptChildExpression(select);
    }

    /**
     * Simplify an expression. This performs any static optimization (by rewriting the expression
     * as a different expression). The default implementation does nothing.
     * @return the simplified expression
     * @throws client.net.sf.saxon.ce.trans.XPathException
     *          if an error is discovered during expression rewriting
     * @param visitor an expression visitor
     */

    public Expression simplify(ExpressionVisitor visitor) throws XPathException {
        select = visitor.simplify(select);
        terminate = visitor.simplify(terminate);
        return this;
    }

    public Expression typeCheck(ExpressionVisitor visitor, ItemType contextItemType) throws XPathException {
        select = visitor.typeCheck(select, contextItemType);
        adoptChildExpression(select);
        if (terminate != null) {
            terminate = visitor.typeCheck(terminate, contextItemType);
            adoptChildExpression(terminate);
        }
        return this;
    }

   public Expression optimize(ExpressionVisitor visitor, ItemType contextItemType) throws XPathException {
        select = visitor.optimize(select, contextItemType);
        adoptChildExpression(select);
        if (terminate != null) {
            terminate = visitor.optimize(terminate, contextItemType);
            adoptChildExpression(terminate);
        }
        return this;
    }


    /**
    * Get the name of this instruction for diagnostic and tracing purposes
    */

    public int getInstructionNameCode() {
        return StandardNames.XSL_MESSAGE;
    }

    /**
     * Get the item type. To avoid spurious compile-time type errors, we falsely declare that the
     * instruction can return anything
     * @param th the type hierarchy cache
     * @return AnyItemType
     */
    public ItemType getItemType(TypeHierarchy th) {
        return AnyItemType.getInstance();
    }

    /**
     * Get the static cardinality. To avoid spurious compile-time type errors, we falsely declare that the
     * instruction returns zero or one items - this is always acceptable
     * @return zero or one
     */

    public int getCardinality() {
        return StaticProperty.ALLOWS_ZERO_OR_ONE;
    }

    /**
     * Determine whether this instruction creates new nodes.
     * This implementation returns true.
     */

    public final boolean createsNewNodes() {
        return true;
    }
    /**
     * Handle promotion offers, that is, non-local tree rewrites.
     * @param offer The type of rewrite being offered
     * @throws XPathException
     */

    protected void promoteInst(PromotionOffer offer) throws XPathException {
        if (select != null) {
            select = doPromotion(select, offer);
        }
        if (terminate != null) {
            terminate = doPromotion(terminate, offer);
        }
    }

    /**
     * Get all the XPath expressions associated with this instruction
     * (in XSLT terms, the expression present on attributes of the instruction,
     * as distinct from the child instructions in a sequence construction)
     */

    public Iterator<Expression> iterateSubExpressions() {
        ArrayList list = new ArrayList(2);
        if (select != null) {
            list.add(select);
        }
        if (terminate != null) {
            list.add(terminate);
        }
        return list.iterator();
    }

    /**
     * Replace one subexpression by a replacement subexpression
     * @param original the original subexpression
     * @param replacement the replacement subexpression
     * @return true if the original subexpression is found
     */

    public boolean replaceSubExpression(Expression original, Expression replacement) {
        boolean found = false;
        if (select == original) {
            select = replacement;
            found = true;
        }
        if (terminate == original) {
            terminate = replacement;
            found = true;
        }
        return found;
    }
    
    public TailCall processLeavingTail(XPathContext context) throws XPathException {
        ValueRepresentation content =
                ExpressionTool.evaluate(select, ExpressionTool.ITERATE_AND_MATERIALIZE, context, 1);
        String message = content.getStringValue();         
        boolean abort = false;
        if (terminate != null) {
            String term = terminate.evaluateAsString(context).toString();
            if (term.equals("no")) {
                // no action
            } else if (term.equals("yes")) {
                abort = true;
            } else {
                XPathException e = new XPathException("The terminate attribute of xsl:message must be 'yes' or 'no'");
                e.setXPathContext(context);
                e.setErrorCode("XTDE0030");
                throw e;
            }
        }

        logger.log(Level.INFO, message);

        if (abort) {
            throw new TerminationException(
                    "Processing terminated by xsl:message in " + StandardErrorListener.abbreviatePath(getSystemId()));
        }
        return null;
    }


}
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. 
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is “Incompatible With Secondary Licenses”, as defined by the Mozilla Public License, v. 2.0.
