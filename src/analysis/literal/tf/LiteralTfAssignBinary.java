package analysis.literal.tf;

import java.util.*;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.literal.LiteralLatticeElement;
import conversion.TacPlace;
import conversion.Variable;
import conversion.nodes.CfgNode;

// transfer function for binary assignment nodes
public class LiteralTfAssignBinary
extends TransferFunction {

    private Variable left;
    private TacPlace leftOperand;
    private TacPlace rightOperand;
    private int op;
    private Set mustAliases;
    private Set mayAliases;
    private CfgNode cfgNode;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    // mustAliases, mayAliases: of setMe
    public LiteralTfAssignBinary(TacPlace left, TacPlace leftOperand, TacPlace rightOperand,
            int op, Set mustAliases, Set mayAliases, CfgNode cfgNode) {
        
        this.left = (Variable) left;  // must be a variable
        this.leftOperand = leftOperand;
        this.rightOperand = rightOperand;
        this.op = op;
        this.mustAliases = mustAliases;
        this.mayAliases = mayAliases;
        this.cfgNode = cfgNode;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        LiteralLatticeElement in = (LiteralLatticeElement) inX;
        LiteralLatticeElement out = new LiteralLatticeElement(in);

        // let the lattice element handle the details
        out.assignBinary(left, leftOperand, rightOperand, op, 
                mustAliases, mayAliases, cfgNode);
        
        return out;
    }
}
