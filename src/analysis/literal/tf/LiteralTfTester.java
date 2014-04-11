package analysis.literal.tf;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.literal.LiteralLatticeElement;
import conversion.Literal;
import conversion.Variable;
import conversion.nodes.CfgNodeTester;

// transfer function for special ~_test_ node
public class LiteralTfTester
extends TransferFunction {
    
    // provides access to the return variable of the function enclosing
    // this ~_test_ node
    private Variable retVar;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public LiteralTfTester(CfgNodeTester cfgNode) {
        this.retVar = (Variable) cfgNode.getEnclosingFunction().getRetVar();
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        LiteralLatticeElement in = (LiteralLatticeElement) inX;
        LiteralLatticeElement out = new LiteralLatticeElement(in);

        // this one is easy: just set the return variable to TOP
        out.setRetVar(this.retVar, Literal.TOP);
        return out;
    }
}
