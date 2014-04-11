package analysis.dep.tf;

import java.util.*;
import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.dep.Dep;
import analysis.dep.DepLatticeElement;
import analysis.dep.DepSet;
import conversion.nodes.CfgNodeCallBuiltin;

public class DepTfCallBuiltin
extends TransferFunction {

    private CfgNodeCallBuiltin cfgNode;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public DepTfCallBuiltin(CfgNodeCallBuiltin cfgNode) {
        this.cfgNode = cfgNode;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {
        
        DepLatticeElement in = (DepLatticeElement) inX;
        DepLatticeElement out = new DepLatticeElement(in);
        
        // create an appropariate taint value (holding the function's name);
        // the array label is identic to the taint value
        Set<Dep> ets = new HashSet<Dep>();
        ets.add(Dep.create(this.cfgNode));
        DepSet retDepSet = DepSet.create(ets);
        DepSet retArrayLabel = retDepSet;
        
        // assign this taint/label to the node's temporary
        out.handleReturnValueBuiltin(this.cfgNode.getTempVar(), retDepSet, retArrayLabel);

        return out;
        
    }

}



