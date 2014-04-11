package conversion.nodes;

import phpParser.*;
import conversion.TacPlace;
import conversion.Variable;

import java.util.*;


// *********************************************************************************
// CfgNodeEcho ********************************************************************
// *********************************************************************************


public class CfgNodeEcho
extends CfgNode {

    private TacPlace place;
    
// CONSTRUCTORS ********************************************************************    

    public CfgNodeEcho(TacPlace place, ParseNode node) {
        super(node);
        this.place = place;
    }

//  GET *****************************************************************************
    
    public TacPlace getPlace() {
        return this.place;
    }
    
    public List<Variable> getVariables() {
        if (this.place instanceof Variable) {
            List<Variable> retMe = new LinkedList<Variable>();
            retMe.add((Variable) this.place);
            return retMe;
        } else {
            return Collections.emptyList();
        }
    }
    
//  SET ****************************************************************************

    public void replaceVariable(int index, Variable replacement) {
        switch (index) {
        case 0:
            this.place = replacement;
            break;
        default:
            throw new RuntimeException("SNH");
        }
    }

}

