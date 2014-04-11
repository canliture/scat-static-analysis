package conversion;

import java.util.*;

import phpParser.ParseNode;
import pixy.MyOptions;
import pixy.Utils;

public class TacClass {

    // node where the class definition starts
    private ParseNode parseNode;
    
    // the name of the class
    private String name;
    // the name of the Superclass
    private String superClassName;
    private TacClass superClass;
    
    private Map<String,TacInterface> ImplementedInterfaces;
    
 // is this class abstract?
    private boolean isAbstrace;
    
    
 // is this class final?
    private boolean isFinal;
    
    // method name -> TacFunction
    private Map<String,TacFunction> methods;
    
    // member name -> Pair(initializer cfg, TacPlace that summarizes the cfg) 
    private Map<String,TacMember> members;
    
    TacClass(String name, ParseNode parseNode) {
        this.name = name;
        this.methods = new HashMap<String,TacFunction>();
        this.members = new HashMap<String,TacMember>();
        this.parseNode = parseNode;
        this.superClassName="";
        this.superClass=null;
        this.ImplementedInterfaces = new HashMap<String,TacInterface>();
    }

    // if this class already contains a method with the given name,
    // false is returned
    boolean addMethod(String name, TacFunction function) {
        if (this.methods.get(name) == null) {
            this.methods.put(name, function);
            return true;
        } else {
            return false;
        }
    }
    
    public boolean addImplmentedInterface(String name, TacInterface ImplmentedInterface) {
        if (this.getImplementedInterfaces().get(name) == null) {
            this.getImplementedInterfaces().put(name, ImplmentedInterface);
            return true;
        } else {
            return false;
        }
    }
    
    public String getName() {
        return this.name;
    }
    
    public String getSuperClassName() {
        return this.superClassName;
    }
    public TacClass getSuperClass() {
		return superClass;
	}

    public boolean getIsFinal() {
        return this.isFinal;
    }
    
	public Map<String,TacInterface> getImplementedInterfaces() {
		return ImplementedInterfaces;
	}
	
    public boolean getIsAbstrace() {
        return this.isAbstrace;
    }
    
    public void setIsFinal(boolean value) {
        isFinal=value;
    }
    public void setSuperClassName(String value) {
    	superClassName=value;
    }
    
    public void setSuperClass(TacClass value) {
  		this.superClass=value;
  	}

    public void setIsAbstrace(boolean value) {
    	isAbstrace=value;
    }
    
    public String getFileName() {
        return this.parseNode.getFileName();
    }
    
    public int getLine() {
        return this.parseNode.getLinenoLeft();
    }
    
    public String getLoc() {
        if (!MyOptions.optionB) {
            return this.parseNode.getFileName() + ":" + this.parseNode.getLinenoLeft();
        } else {
            return Utils.basename(this.parseNode.getFileName()) + ":" + this.parseNode.getLinenoLeft();
        }
    }
    
    public void addMember(String name, Cfg cfg, TacPlace place) {
        TacMember member = new TacMember(name, cfg, place);
        this.members.put(name, member);
    }
    
    public String dump() {
        StringBuilder b = new StringBuilder();
        b.append("Class ");
        b.append(this.name);
        b.append("\n");
        b.append("Functions:\n");
        for (String methodName : this.methods.keySet()) {
            b.append(methodName);
            b.append("\n");
        }
        b.append("Members:\n");
        for (TacMember member : this.members.values()) {
            b.append(member.dump());
        }
        b.append("\n");
        
        return b.toString();
    }
    
// TacMember (private class) *******************************************************
    
   





	private class TacMember {
        
        // member name
        private String name;
        
        // initializer cfg
        private Cfg cfg;
        
        // place that summarizes the initializer cfg; e.g., if you have
        // a member declaration such as 
        private TacPlace place;
        
        TacMember(String name, Cfg cfg, TacPlace place) {
            this.name = name;
            this.cfg = cfg;
            this.place = place;
        }
        
        String getName() {
            return this.name;
        }
        
        Cfg getCfg() {
            return this.cfg;
        }
        
        TacPlace getPlace() {
            return this.place;
        }
        
        String dump() {
            StringBuilder b = new StringBuilder();
            b.append(this.name);
            b.append("\n");
            return b.toString();
        }
    }
}
