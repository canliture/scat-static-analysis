package analysis.alias.tf;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.alias.AliasAnalysis;
import analysis.alias.AliasLatticeElement;
import conversion.TacPlace;
import conversion.Variable;

// transfer function for simple assignment nodes
public class AliasTfUnset
extends TransferFunction {

    private Variable operand;
    private AliasAnalysis aliasAnalysis;
    private boolean supported;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public AliasTfUnset(TacPlace operand, AliasAnalysis aliasAnalysis) {
        
        // only variables can be unset
        if (!operand.isVariable()) {
            throw new RuntimeException("Trying to unset a non-variable.");
        }
        
        this.operand = operand.getVariable();
        this.aliasAnalysis = aliasAnalysis;
        this.supported = AliasAnalysis.isSupported(this.operand);

    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        AliasLatticeElement in = (AliasLatticeElement) inX;
        
        // if the operand is not supported for reference statements:
        // nothing to do, since we don't support such aliases
        if (!this.supported) {
            return in;
        }
        
        AliasLatticeElement out = new AliasLatticeElement(in);
        
        // perform unset operation on "out"
        out.unset(this.operand);

        // recycle
        out = (AliasLatticeElement) this.aliasAnalysis.recycle(out);

        return out;
    }
}
