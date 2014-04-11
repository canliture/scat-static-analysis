package analysis.type.tf;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.type.TypeLatticeElement;
import conversion.nodes.CfgNodeCallBuiltin;

public class TypeTfCallBuiltin
extends TransferFunction {

    private CfgNodeCallBuiltin cfgNode;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public TypeTfCallBuiltin(CfgNodeCallBuiltin cfgNode) {
        this.cfgNode = cfgNode;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        TypeLatticeElement in = (TypeLatticeElement) inX;
        TypeLatticeElement out = new TypeLatticeElement(in);

        out.handleReturnValueBuiltin(this.cfgNode.getTempVar());
        
        return out;
    }
}
