package analysis.literal.tf;


import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.inter.Context;
import analysis.literal.LiteralLatticeElement;
import conversion.Literal;
import conversion.nodes.CfgNodeCallRet;

public class LiteralTfCallRetUnknown
extends TransferFunction {

    private CfgNodeCallRet retNode;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public LiteralTfCallRetUnknown(CfgNodeCallRet retNode) {
        this.retNode = retNode;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX, Context context) {
        
        LiteralLatticeElement in = (LiteralLatticeElement) inX;
        LiteralLatticeElement out = new LiteralLatticeElement(in);

        out.handleReturnValueUnknown(this.retNode.getTempVar());

        return out;
        
    }
    
    // just a dummy method in order to make me conform to the interface;
    // the Analysis uses the other transfer method instead
    public LatticeElement transfer(LatticeElement inX) {
        throw new RuntimeException("SNH");
    }
    

}



