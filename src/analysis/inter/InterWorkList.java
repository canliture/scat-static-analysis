package analysis.inter;

import conversion.nodes.CfgNode;

public interface InterWorkList {

    void add(CfgNode cfgNode, Context context);
    InterWorkListElement removeNext();
    boolean hasNext();
    
}
