package analysis.inter;

import java.util.*;

import conversion.nodes.CfgNodeCall;

public class ReverseTarget {

    private CfgNodeCall callNode;
    
    // a set of Contexts
    private Set<? extends Context> contexts;
    
//  *********************************************************************************    
//  CONSTRUCTORS ********************************************************************
//  ********************************************************************************* 

    public ReverseTarget(CfgNodeCall callNode, Set<? extends Context> contexts) {
        this.callNode = callNode;
        this.contexts = contexts;
    }

//  *********************************************************************************    
//  GET *****************************************************************************
//  ********************************************************************************* 

    public CfgNodeCall getCallNode() {
        return this.callNode;
    }
    
    public Set<? extends Context> getContexts() {
        return this.contexts;
    }
}
