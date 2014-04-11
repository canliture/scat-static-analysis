package analysis.literal.tf;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.literal.LiteralLatticeElement;
import conversion.nodes.CfgNodeCallUnknown;

public class LiteralTfCallUnknown
extends TransferFunction {

    private CfgNodeCallUnknown cfgNode;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public LiteralTfCallUnknown(CfgNodeCallUnknown cfgNode) {
        this.cfgNode = cfgNode;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {
        
        LiteralLatticeElement in = (LiteralLatticeElement) inX;
        LiteralLatticeElement out = new LiteralLatticeElement(in);
        
        // for an unknown function, return TOP
        out.handleReturnValueUnknown(this.cfgNode.getTempVar());

        return out;
        
    }

}



