package analysis.literal.tf;

import java.util.*;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.alias.AliasAnalysis;
import analysis.literal.LiteralLatticeElement;
import conversion.Literal;
import conversion.TacPlace;
import conversion.Variable;

// transfer function for unset nodes
public class LiteralTfUnset
extends TransferFunction {

    private Variable operand;
    private boolean supported;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public LiteralTfUnset(TacPlace operand) {

        // only variables can be unset
        if (!operand.isVariable()) {
            throw new RuntimeException("Trying to unset a non-variable.");
        }
        
        this.operand = (Variable) operand;
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
        
        LiteralLatticeElement in = (LiteralLatticeElement) inX;
        LiteralLatticeElement out = new LiteralLatticeElement(in);

        // unsetting a variable means setting it to null for literal analysis
        Set<Variable> mustAliases = new HashSet<Variable>();
        mustAliases.add(operand);
        Set mayAliases = Collections.EMPTY_SET;
        out.assignSimple(operand, Literal.NULL, mustAliases, mayAliases);
        
        return out;
    }
}
