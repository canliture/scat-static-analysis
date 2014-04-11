package analysis.inter;

import analysis.inter.callstring.CallString;
import conversion.TacFunction;

// worklist element for connector computation
public final class ConnectorWorkListElement {

    private final TacFunction function;
    private final CallString callString;

// *********************************************************************************    
// CONSTRUCTORS ********************************************************************
// *********************************************************************************    
    
    ConnectorWorkListElement(TacFunction function, CallString callString) {
        this.function = function;
        this.callString = callString;
    }
    
// *********************************************************************************    
// GET *****************************************************************************
// *********************************************************************************    
    
    TacFunction getFunction() {
        return this.function;
    }

    CallString getCallString() {
        return this.callString;
    }
}
