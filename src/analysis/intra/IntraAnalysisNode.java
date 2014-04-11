package analysis.intra;

import analysis.AnalysisNode;
import analysis.LatticeElement;
import analysis.TransferFunction;



// an AnalysisNode holds analysis-specific information for a certain CFGNode
public class IntraAnalysisNode 
extends AnalysisNode {

    // input lattice element at current CFG node
    LatticeElement inValue;

// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// ********************************************************************************* 
    
    protected IntraAnalysisNode(TransferFunction tf) {
        super(tf);
        this.inValue = null;
    }
    
// *********************************************************************************
// GET *****************************************************************************    
// *********************************************************************************
    
    public LatticeElement getInValue() {
        return this.inValue;
    }

// *********************************************************************************
// SET *****************************************************************************
// *********************************************************************************

    protected void setInValue(LatticeElement inValue) {
        this.inValue = inValue;
    }
    
// *********************************************************************************
// OTHER ***************************************************************************
// *********************************************************************************

}

