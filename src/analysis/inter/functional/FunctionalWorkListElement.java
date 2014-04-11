package analysis.inter.functional;

import analysis.LatticeElement;
import conversion.nodes.CfgNode;

public final class FunctionalWorkListElement {

    private final CfgNode cfgNode;
    private final LatticeElement context;

// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************    
    
    FunctionalWorkListElement(CfgNode cfgNode, LatticeElement context) {
        this.cfgNode = cfgNode;
        this.context = context;
    }
    
// *********************************************************************************    
// GET *****************************************************************************
// *********************************************************************************    
    
    CfgNode getCfgNode() {
        return this.cfgNode;
    }

    LatticeElement getContext() {
        return this.context;
    }
}
