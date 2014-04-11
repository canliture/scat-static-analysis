package analysis.type.tf;


import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.type.TypeLatticeElement;
import conversion.TacPlace;
import conversion.Variable;

// transfer function for reference assignment nodes
public class TypeTfAssignRef
extends TransferFunction {

    private Variable left;
    private TacPlace right;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public TypeTfAssignRef(Variable left, TacPlace right) {
        this.left = left;
        this.right = right;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        TypeLatticeElement in = (TypeLatticeElement) inX;
        TypeLatticeElement out = new TypeLatticeElement(in);

        // let the lattice element handle the details
        out.assign(left, right);
        
        return out;
    }
}
