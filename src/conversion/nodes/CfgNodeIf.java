package conversion.nodes;

import phpParser.*;
import conversion.Constant;
import conversion.TacPlace;
import conversion.Variable;

import java.util.*;


//*********************************************************************************
//CfgNodeIf ***********************************************************************
//*********************************************************************************


public class CfgNodeIf
extends CfgNode {

    private TacPlace leftOperand;
    private TacPlace rightOperand;  // may only be Constant.TRUE or Constant.FALSE
    private int op;
 
//CONSTRUCTORS ********************************************************************    

    public CfgNodeIf (TacPlace leftOperand, TacPlace rightOperand, int op, ParseNode node) {
        super(node);
        // make sure that right operand is valid (i.e. true or false)
        if (!(rightOperand == Constant.TRUE || rightOperand == Constant.FALSE)) {
            throw new RuntimeException(
                "SNH: illegal right operand for if node at line " + 
                node.getLinenoLeft());
        }
        this.leftOperand = leftOperand;
        this.rightOperand = rightOperand;
        this.op = op;
    }

    public TacPlace getLeftOperand() {
        return this.leftOperand;
    }
    
    public TacPlace getRightOperand() {
        return this.rightOperand;
    }
    
    public int getOperator() {
        return this.op;
    }
    
    public List<Variable> getVariables() {
        List<Variable> retMe = new LinkedList<Variable>();
        if (this.leftOperand instanceof Variable) {
            retMe.add((Variable) this.leftOperand);
        } else {
            retMe.add(null);
        }
        return retMe;
    }
    
//  SET ****************************************************************************

    public void replaceVariable(int index, Variable replacement) {
        switch (index) {
        case 0:
            this.leftOperand = replacement;
            break;
        default:
            throw new RuntimeException("SNH");
        }
    }

}


