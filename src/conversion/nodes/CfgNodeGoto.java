package conversion.nodes;

import phpParser.*;
import conversion.TacPlace;
import conversion.Variable;

import java.util.*;

// *********************************************************************************
// CfgNodeThrow ******************************************************************
// *********************************************************************************

public class CfgNodeGoto
extends CfgNode {
	private TacPlace GotoString;
// CONSTRUCTORS ********************************************************************
    
    public CfgNodeGoto(TacPlace GotoString, ParseNode parseNode) {
        super(parseNode);
        this.GotoString = GotoString;
    }
    
//  GET ****************************************************************************
    public TacPlace getGotoString() {
        return this.GotoString;
    }
    
    public Variable getRetVar() {
        return this.getCallNode().getRetVar();
    }
    
    public Variable getTempVar() {
        return this.getCallNode().getTempVar();
    }
    
    public CfgNodeCallPrep getCallPrepNode() {
        return (CfgNodeCallPrep) this.getPredecessor().getPredecessor();
    }
    
    public CfgNodeCall getCallNode() {
        return (CfgNodeCall) this.getPredecessor();
    }
    
    List getParamsList() {
        return this.getCallPrepNode().getParamList();
    }
    
    // not relevant for globals replacement
    public List<Variable> getVariables() {
        return Collections.emptyList();
    }
    
//  SET ****************************************************************************

    public void replaceVariable(int index, Variable replacement) {
        // do nothing
    }
    
    public void setRetVar(Variable retVar) {
        this.getCallNode().setRetVar(retVar);
    }


}


