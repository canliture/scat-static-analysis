package analysis.dep.tf;

import java.util.Set;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.dep.DepLatticeElement;
import conversion.TacPlace;
import conversion.Variable;
import conversion.nodes.CfgNode;

// transfer function for simple assignment nodes
public class DepTfAssignSimple
extends TransferFunction {

    private Variable left;
    private Set mustAliases;
    private Set mayAliases;
    private CfgNode cfgNode;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public DepTfAssignSimple(TacPlace left, TacPlace right, 
            Set mustAliases, Set mayAliases, CfgNode cfgNode) {
        
        this.left = (Variable) left;  // must be a variable
        this.mustAliases = mustAliases;
        this.mayAliases = mayAliases;
        this.cfgNode = cfgNode;

    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        //System.out.println("assignsimple: " + left + " = " + right);
        
        DepLatticeElement in = (DepLatticeElement) inX;
        DepLatticeElement out = new DepLatticeElement(in);

        // let the lattice element handle the details
        out.assign(left, mustAliases, mayAliases, cfgNode);
        
        return out;
    }
}
