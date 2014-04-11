package analysis.type.tf;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.type.TypeLatticeElement;
import conversion.Variable;

// transfer function for unary assignment nodes
public class TypeTfAssignUnary
extends TransferFunction {

    private Variable left;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    // mustAliases, mayAliases: of setMe
    public TypeTfAssignUnary(Variable left) {
        this.left = left;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        TypeLatticeElement in = (TypeLatticeElement) inX;
        TypeLatticeElement out = new TypeLatticeElement(in);

        // let the lattice element handle the details
        out.assignUnary(left);
        
        return out;
    }
}
