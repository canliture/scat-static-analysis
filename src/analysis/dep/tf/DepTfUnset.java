package analysis.dep.tf;

import java.util.*;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.alias.AliasAnalysis;
import analysis.dep.*;
import conversion.Literal;
import conversion.TacPlace;
import conversion.Variable;
import conversion.nodes.CfgNode;

// transfer function for unset nodes
public class DepTfUnset
extends TransferFunction {

    private Variable operand;
    private CfgNode cfgNode;
    private boolean supported;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public DepTfUnset(TacPlace operand, CfgNode cfgNode) {

        // only variables can be unset
        if (!operand.isVariable()) {
            throw new RuntimeException("Trying to unset a non-variable.");
        }
        
        this.operand = (Variable) operand;
        this.cfgNode = cfgNode;
        this.supported = AliasAnalysis.isSupported(this.operand);
        
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        // if this statement is not supported by our alias analysis,
        // we simply ignore it
        if (!supported) {
            return inX;
        }
        
        DepLatticeElement in = (DepLatticeElement) inX;
        DepLatticeElement out = new DepLatticeElement(in);

        // unsetting a variable means setting it to NULL (untainted/clean)
        Set<Variable> mustAliases = new HashSet<Variable>();
        mustAliases.add(operand);
        Set mayAliases = Collections.EMPTY_SET;
        out.assign(operand, mustAliases, mayAliases, cfgNode);
        
        return out;
    }
}
