package analysis.type.tf;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.type.TypeLatticeElement;
import conversion.Variable;

public class TypeTfAssignArray
extends TransferFunction {

    private Variable operand;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public TypeTfAssignArray(Variable operand) {
        this.operand = operand;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        TypeLatticeElement in = (TypeLatticeElement) inX;
        TypeLatticeElement out = new TypeLatticeElement(in);

        out.assignArray(operand);
        
        return out;
    }
}
