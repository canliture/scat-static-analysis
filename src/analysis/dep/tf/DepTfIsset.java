package analysis.dep.tf;

import java.util.*;

import analysis.LatticeElement;
import analysis.TransferFunction;
import analysis.dep.DepLatticeElement;
import conversion.Literal;
import conversion.TacPlace;
import conversion.Variable;
import conversion.nodes.CfgNode;

// transfer function for "isset" tests
// LATER: make it intelligent
public class DepTfIsset 
extends TransferFunction{
    
    private Variable setMe;
    private TacPlace testMe;
    private CfgNode cfgNode;
    
// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************     

    public DepTfIsset(TacPlace setMe, TacPlace testMe, CfgNode cfgNode) {
        this.setMe = (Variable) setMe;  // must be a variable
        this.testMe = testMe;
        this.cfgNode = cfgNode;
    }

// *********************************************************************************    
// OTHER ***************************************************************************
// *********************************************************************************  

    public LatticeElement transfer(LatticeElement inX) {

        // System.out.println("transfer method: " + setMe + " = " + setTo);
        DepLatticeElement in = (DepLatticeElement) inX;
        DepLatticeElement out = new DepLatticeElement(in);

        if (!setMe.isTemp()) {
            throw new RuntimeException("SNH");
        }
        
        // always results in a boolean, which is always untainted/clean;
        // not so elegant, but working: simply use Literal.FALSE
        Set<Variable> mustAliases = new HashSet<Variable>();
        mustAliases.add(setMe);
        Set mayAliases = Collections.EMPTY_SET;
        out.assign(setMe, mustAliases, mayAliases, cfgNode);
        
        return out;
    }
}
