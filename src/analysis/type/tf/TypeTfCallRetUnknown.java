package analysis.type.tf;


import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.inter.Context;
import analysis.type.TypeLatticeElement;
import conversion.nodes.CfgNodeCallRet;

public class TypeTfCallRetUnknown
extends TransferFunction {

    private CfgNodeCallRet retNode;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public TypeTfCallRetUnknown(CfgNodeCallRet retNode) {
        this.retNode = retNode;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX, Context context) {
        
        TypeLatticeElement in = (TypeLatticeElement) inX;
        TypeLatticeElement out = new TypeLatticeElement(in);

        out.handleReturnValueUnknown(this.retNode.getTempVar());

        return out;
        
    }
    
    // just a dummy method in order to make me conform to the interface;
    // the Analysis uses the other transfer method instead
    public LatticeElement transfer(LatticeElement inX) {
        throw new RuntimeException("SNH");
    }
    

}



