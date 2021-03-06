package unifi.drivers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.LineNumberTable;
import org.apache.bcel.classfile.LocalVariableTable;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ALOAD;
import org.apache.bcel.generic.BranchInstruction;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.DLOAD;
import org.apache.bcel.generic.FLOAD;
import org.apache.bcel.generic.FieldInstruction;
import org.apache.bcel.generic.GETFIELD;
import org.apache.bcel.generic.GETSTATIC;
import org.apache.bcel.generic.INVOKESTATIC;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.LDC;
import org.apache.bcel.generic.LLOAD;
import org.apache.bcel.generic.LocalVariableInstruction;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.ObjectType;
import org.apache.bcel.generic.TargetLostException;
import org.apache.bcel.generic.Type;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import seaview.IPInfo;
import unifi.MethodUnits;
import unifi.oo.MethodResolver;
import unifi.rd.LogicalLVMap;
import unifi.rd.RD_DF_algorithm;
import unifi.units.FieldUnit;
import unifi.units.ReturnValueUnit;
import unifi.units.Unit;

import com.google.gson.Gson;

public class Instrumenter {
	// instruments the jar file named 'name' and writes the instrumented
	// file to jout
	public static Log log = LogFactory.getLog(Instrumenter.class);
	public static String TMP_DIR = "SEAVIEW";

	public static void instrumentJar (String name) throws IOException, TargetLostException
	{
	    Analyze.Tee ("Examining all classes in jar file \"" + name + '"');

	    JarInputStream jin = new JarInputStream (new FileInputStream(name));
	    JarFile jf = new JarFile (name);
	    Enumeration e = jf.entries();

	    while (e.hasMoreElements())
	    {
	        JarEntry je = (JarEntry) e.nextElement();
	        if (je == null)
	            break;

	        // no copy for dirs
	        if (je.isDirectory())
	            continue;

	        if (je.getName().endsWith (".class"))
	        {
	            instrumentClass (Analyze.get_clazz_from_istream (je.getName(), jf.getInputStream (je)));
	        }
	    }

	    jin.close();
	}

	private static void writeInstrumentedClass(String name, byte[] bytes)
	{

	    try
	    {
			//check if name and clazz.getClassName() are same
		        // String class_name_with_slash = clazz.getClassName().replace ('.', File.separatorChar);
		    String class_name_with_slash = name.replace ('.', File.separatorChar);
			/**
			 * to write an instrumented file, the tmp directory should contain
			 * class's package path directory structure beneath it.
			 */ 
		    String instrumented_class_filename = null;

			String package_pathname = "";
			int package_path_index;	
			if ((package_path_index = class_name_with_slash.lastIndexOf(File.separatorChar)) != -1) 
			     package_pathname = class_name_with_slash.substring(0, package_path_index);

			String instrumented_dirname = TMP_DIR + File.separatorChar + package_pathname;
		    instrumented_class_filename = (TMP_DIR + File.separatorChar + class_name_with_slash) + ".class";
	
			File tmpDir = new File(instrumented_dirname);
			tmpDir.mkdirs();
			File instFile = new File(instrumented_class_filename);
			FileOutputStream out = new FileOutputStream(instFile);
			out.write(bytes);
			out.close();
	    } catch (Exception e) { e.printStackTrace(); System.exit (2); }
	}
	
	// instruments the class whose name is passed
	// filename MUST BE in format a/b/c.class
	public static byte[] instrumentClass (JavaClass clazz, InputStream is2) throws IOException, TargetLostException
	{
		String name = clazz.getClassName();
	    Analyze.Tee ("Examining class \"" + name + '"');
	    name = name.substring (0, name.length()-".class".length());
	    byte[] instrumentedBytes = instrumentClass(clazz);
	    return instrumentedBytes;
	}

	static byte[] instrumentClass (JavaClass clazz) throws IOException, TargetLostException
	{
	    Method[] methods = clazz.getMethods();
	    Analyze.Tee (methods.length + " methods");

	    // error check
	    ConstantPoolGen cpgen = new ConstantPoolGen (clazz.getConstantPool());
	    if (cpgen.lookupClass ("seaview.Runtime") != -1)
	    {
	    	Analyze.Tee ("ERROR: \"" + clazz.getClassName() + "\" previously instrumented!!");
	    	throw new RuntimeException();
	    }

	    for (int i = 0; i < methods.length; i++)
	    {
	    	Method method = methods[i];
	        Analyze.Tee ("Instrumenting method " + clazz.getClassName() + "." + method.getName() + method.getSignature());
	        MethodGen mg = new MethodGen(method, clazz.getClassName(), cpgen);

		    if (method.isNative() || method.isAbstract())
		    {
	        	Analyze.Tee ("(abstract or native)");
	        	continue;		    	
		    }
	           
		    methods[i] = instrumentMethod (mg);
	    }
	    clazz.setConstantPool(cpgen.getFinalConstantPool());
	    byte[] instrumentedBytes = clazz.getBytes();
	    writeInstrumentedClass(clazz.getClassName(), instrumentedBytes);

	    return instrumentedBytes;
	}

	static List<IPInfo> IPInfos = new ArrayList<IPInfo>();
	static List<String> logEndCalls = new ArrayList<String>();
	static List<String> logStartCalls = new ArrayList<String>();
	static List<String> logAppendCalls = new ArrayList<String>();
	static {
		// todo: make all these robust with regexps
		logStartCalls.add("java.lang.StringBuilder.<init>(Ljava/lang/String;)V");
		logStartCalls.add("java.lang.StringBuilder.<init>()V");
		logStartCalls.add("java.lang.StringBuffer.<init>(Ljava/lang/String;)V");
		logStartCalls.add("java.lang.StringBuffer.<init>()V");
		
		logAppendCalls.add("java.lang.StringBuilder.append(I)Ljava/lang/StringBuilder;");
		logAppendCalls.add("java.lang.StringBuilder.append(J)Ljava/lang/StringBuilder;");
		logAppendCalls.add("java.lang.StringBuilder.append(B)Ljava/lang/StringBuilder;");
		logAppendCalls.add("java.lang.StringBuilder.append(C)Ljava/lang/StringBuilder;");
		logAppendCalls.add("java.lang.StringBuilder.append(S)Ljava/lang/StringBuilder;");
		logAppendCalls.add("java.lang.StringBuilder.append(Z)Ljava/lang/StringBuilder;");
		logAppendCalls.add("java.lang.StringBuilder.append(F)Ljava/lang/StringBuilder;");
		logAppendCalls.add("java.lang.StringBuilder.append(D)Ljava/lang/StringBuilder;");
		logAppendCalls.add("java.lang.StringBuilder.append(Ljava/lang/Object;)Ljava/lang/StringBuilder;");

		logEndCalls.add("org.apache.commons.logging.Log.trace(Ljava/lang/Object;)V");
		logEndCalls.add("org.apache.commons.logging.Log.debug(Ljava/lang/Object;)V");
		logEndCalls.add("org.apache.commons.logging.Log.info(Ljava/lang/Object;)V");
		logEndCalls.add("org.apache.commons.logging.Log.warn(Ljava/lang/Object;)V");
		logEndCalls.add("org.apache.commons.logging.Log.error(Ljava/lang/Object;)V");
		logEndCalls.add("org.apache.commons.logging.Log.fatal(Ljava/lang/Object;)V");
		logEndCalls.add("java.util.logging.Logger.log(Ljava/util/logging/Level;Ljava/lang/String;)V");
		logEndCalls.add("java.util.logging.Logger.warning(Ljava/lang/String;)V");
		logEndCalls.add("java.util.logging.Logger.severe(Ljava/lang/String;)V");
		logEndCalls.add("java.util.logging.Logger.info(Ljava/lang/String;)V");
		logEndCalls.add("java.util.logging.Logger.fine(Ljava/lang/String;)V");
		logEndCalls.add("java.util.logging.Logger.finer(Ljava/lang/String;)V");
		logEndCalls.add("java.util.logging.Logger.finest(Ljava/lang/String;)V");
	}
	

	private static InstructionList createInvokeRuntimeInsns (String valueSig, ConstantPoolGen cpgen, Unit u, int callSiteNum, boolean isLongOrDouble, String className, String methodName, String methodSig, int lineNum)
	{
		// we convert sb.append(v) -> Runtime.log(sb, v, ipnum);
		InstructionList result = new InstructionList();
		int IPNum = IPInfo.store_IP_info(u, callSiteNum, valueSig, className, methodName, methodSig, lineNum);
		
		// for any object, we have to set the static type for the log call is Object
		if (valueSig.startsWith("L"))
			valueSig = "Ljava/lang/Object;";
		
 	    String runtimeMethodSig = "(Ljava/lang/StringBuilder;" + valueSig + "I)Ljava/lang/StringBuilder;";
	    String runtimeMethodName = "log";

	    int instrument_cp = cpgen.addMethodref ("seaview.Runtime", runtimeMethodName, runtimeMethodSig);
	    // 4 insns inserted: dup, ldc, ldc, invoke
//	    if ("J".equals(valueSig) || "D".equals(valueSig))
//	    	result.append (new DUP2());
//	    else
//	    	result.append (new DUP());
	    result.append (new LDC (cpgen.addInteger(IPNum)));
	    result.append (new INVOKESTATIC (instrument_cp));
	    return result;
	}
	
	static int callSiteNum = 0;

	/* instrument logging calls in a method, begin scanning for logAppendCalls in a logStartCalls...logEndCalls window */
	private static Method instrumentMethod (MethodGen mg) throws TargetLostException
	{
        LineNumberTable lnt = null;
        Method method = mg.getMethod();
        if (method.getCode() != null)
        {
            Attribute[] attribs = method.getCode().getAttributes();
            if (attribs != null)
                for (Attribute a: attribs)
                    if (a instanceof LineNumberTable)
                       lnt = (LineNumberTable) a;
        }

	    if (lnt == null)
	    	Analyze.Tee ("no line number information for method!");

	    ConstantPoolGen cpgen = mg.getConstantPool ();
	    RD_DF_algorithm rd_alg = new RD_DF_algorithm ();
	    LogicalLVMap lv_map = (LogicalLVMap) rd_alg.analyze_method (mg, cpgen, lnt);
	    lv_map.verify (mg, cpgen);

	    String params_return_sig = mg.getSignature();
	    String className = mg.getClassName();
	    String methodName = mg.getName();
	    MethodUnits current_munits = MethodResolver.lookup (className + "." + methodName + params_return_sig);
	    
	    InstructionList il = mg.getInstructionList();

	    int maxLocals = mg.getMaxLocals();
	    int extraLocals = 0;
	    InstructionHandle sbStartIH = null;
	    
	    // we'll need pos info in the original insn list to look up local var units, so store it in this map
	    Map<InstructionHandle, Integer> originalPos = new LinkedHashMap<InstructionHandle, Integer>();
	    for (InstructionHandle ih = il.getStart(); ih != null; ih = ih.getNext())
	    	originalPos.put (ih, ih.getPosition());

	    for (InstructionHandle ih = il.getStart();
	         ih != null;
	         ih = ih.getNext())
	    {
	        Instruction insn = ih.getInstruction();
	        if (insn instanceof BranchInstruction)
	        {
	        	sbStartIH = null; // abort scanning when we see a branch
	        	continue;
	        }

	        if (insn instanceof InvokeInstruction)
	        {
	        	InvokeInstruction invoke = (InvokeInstruction) insn;
	        	String sig = invoke.getClassName(cpgen) + "." + invoke.getMethodName(cpgen) + invoke.getSignature(cpgen);
	        	if (logStartCalls.contains(sig))
	        	{
	        		sbStartIH = ih; // if sbStartIH is not null, means scanning is on....
	        	}
	        	
	        	if (sbStartIH != null && logEndCalls.contains(sig))
	        	{
	        		// ended scanning, now trace backwards
	        		InstructionHandle sbEndIH = ih;
	        		for (InstructionHandle ih1 = sbStartIH; ih1 != sbEndIH && ih1 != null; ih1 = ih1.getNext())
	        		{
	        			Instruction insn1 = ih1.getInstruction();
	        			if (!(insn1 instanceof InvokeInstruction))
	        				continue;
	        			InvokeInstruction invokeAppend = (InvokeInstruction) insn1;
        				String sig1 = invokeAppend.getClassName(cpgen) + "." + invokeAppend.getMethodName(cpgen) + invokeAppend.getSignature(cpgen);
        				InstructionHandle prev_ih = ih1.getPrev();
        				if (logAppendCalls.contains(sig1))
        				{
        					Instruction prev_insn = prev_ih.getInstruction();
        					int lineNum = (lnt != null) ? lnt.getSourceLine(ih1.getPosition()) : -1;
        					if (prev_insn instanceof GETFIELD || prev_insn instanceof GETSTATIC)
        					{
        						FieldInstruction fi = (FieldInstruction) prev_insn;
        						String fieldSig = ((ObjectType) fi.getReferenceType(cpgen)).getClassName() + "." + fi.getFieldName(cpgen);
        						FieldUnit fu = FieldUnit.lookup(fieldSig);
        						if (fu != null)
        						{
        							log.debug ("FLAG cluster at " + ih1 + " in method " + method + " num: " + fu.seaview_id);
        							InstructionList instrumentedInsns = createInvokeRuntimeInsns(fi.getSignature(cpgen), cpgen, fu, callSiteNum, (fi.produceStack(cpgen) == 2), className, methodName, params_return_sig, lineNum);
        				            il.insert(ih1, instrumentedInsns);
        				            InstructionHandle new_ih1 = ih1.getNext();
        				            il.delete(ih1);
        				            ih1 = new_ih1.getPrev();
        						}
        					}
        					else if (prev_insn instanceof InvokeInstruction)
        					{
        						InvokeInstruction invoke1 = (InvokeInstruction) prev_insn;
        			            Type classType = invoke1.getReferenceType(cpgen);
        			            String calledClassName = "["; // if not object type, must be a call to array.clone()
        			            if (classType instanceof ObjectType)
        			            	calledClassName = ((ObjectType) classType).getClassName();
        			            String target_methname = invoke1.getMethodName (cpgen);
        			            String target_param_sig = invoke1.getSignature (cpgen);
        			            
	        	            	MethodUnits target_munits = null;
	        	                try {
	        	                	target_munits = MethodResolver.lookup (calledClassName + "." + target_methname + target_param_sig);
	        	                } catch(RuntimeException e) {
	        	                	log.error("WARNING! classpath probably incorrect\n\n\n\nSkipping method call to: " + sig + ". Exception is: " + e);
	        	                }

	        	                if (target_munits != null)
	        	                {
		        	                ReturnValueUnit rvu = target_munits.get_return_value_unit();
		        	                if (rvu != null)
		        	                {
						    log.debug ("FLAG cluster at " + ih1 + " RV in method " + method + " num: " + rvu.clusterNum);
						    InstructionList instrumentedInsns = createInvokeRuntimeInsns(invoke1.getReturnType(cpgen).getSignature(), cpgen, rvu, callSiteNum, (invoke1.produceStack(cpgen) == 2), className, methodName, params_return_sig, lineNum);
						    il.insert(ih1, instrumentedInsns);
						    InstructionHandle new_ih1 = ih1.getNext();
						    il.delete(ih1);
						    ih1 = new_ih1.getPrev();
		        	                }
	        	                }
        					}
        					else if (prev_insn instanceof LocalVariableInstruction && prev_insn.produceStack(cpgen) > 0) // similarly handle 
        					{
						    int oPos = originalPos.get(prev_ih);
						    int indx = lv_map.pos_to_logical_LV_num (oPos);
						    Unit u = current_munits.get_local_var_unit (indx); //get its unit element
						    LocalVariableInstruction lv_insn = (LocalVariableInstruction) prev_insn;
						    String lv_type_sig = "I";
						    if (lv_insn instanceof FLOAD)
							lv_type_sig = "F";
						    else if (lv_insn instanceof DLOAD)
							lv_type_sig = "D";
						    else if (lv_insn instanceof LLOAD)
							lv_type_sig = "J";
						    else if (lv_insn instanceof ALOAD)
							lv_type_sig = "Ljava/lang/Object;";
						    if (u != null)
							{
							    InstructionList instrumentedInsns = createInvokeRuntimeInsns(lv_type_sig, cpgen, u, callSiteNum, (prev_insn.produceStack(cpgen) == 2), className, methodName, params_return_sig, lineNum);		        	                	
							    il.insert(ih1, instrumentedInsns);
							    InstructionHandle new_ih1 = ih1.getNext();
							    il.delete(ih1);
							    ih1 = new_ih1.getPrev();
							}
        					}
					}
	        		}
	        		sbStartIH = null;
	        		callSiteNum++;
	        	}
	        }
	    }

	    mg.setMaxStack (mg.getMaxStack()+10);
	    mg.setMaxLocals (maxLocals + extraLocals);
	    mg.setInstructionList (il);

	    // we have to make a special effort to drop the local var table attribute in method.code to avoid:
	    // Exception in thread "main" java.lang.ClassFormatError: LVTT entry for 'groups' in class file edu/stanford/muse/mining/TestHarness does not match any LVT entry
	    Method m = mg.getMethod();
	    Code c = m.getCode();
	    Attribute[] attr = c.getAttributes();
	    List<Attribute> list = new ArrayList<Attribute>();
	    if (attr != null)
	    {
		    for (Attribute a: attr)
		    {
		    	if (!(a instanceof LocalVariableTable))
		    		list.add(a);
		    	else
		    		log.debug ("dropping attribute: LVT");
		    }
		}
	    
	    Attribute newAttrs[] = new Attribute[list.size()];
	    int i = 0;
	    for (Attribute a: list)
	    	newAttrs[i++] = a;
	    c.setAttributes(newAttrs);
	    return m;
	}

	static class TMP { String name; String long_name;}
	static class TMP1 extends TMP { String name; String long_name; boolean is_quant_or_ord, is_equals_compared;}

	public static void write_units(String filename) throws UnsupportedEncodingException, FileNotFoundException
	{
		int max_seaview_rep_id = -1, max_seaview_id = -1;
		Map<Unit,List<Unit>> reps = Unit._current_unit_collection.get_reps();
		for (Unit u: reps.keySet())
		{
			if (u.seaview_rep_id > max_seaview_rep_id)
				max_seaview_rep_id = u.seaview_rep_id;
			for (Unit u1: reps.get(u))
				if (u1.seaview_id > max_seaview_id)
					max_seaview_id = u1.seaview_id;
		}

		Map<Integer, Integer> rep_id_map = new LinkedHashMap<Integer, Integer>();
		TMP1[] rep_names = new TMP1[max_seaview_rep_id+1];
		TMP[] unit_names = new TMP[max_seaview_id+1];
		for (Unit u: reps.keySet())
		{
			String rep_description = u.toVeryShortString();
			int size = reps.get(u).size();
			TMP1 t_rep = new TMP1();
			t_rep.name = rep_description;
			//t_rep.long_name =  size + " unit" + (size > 1?"s":"") + ", e.g.: " + u.toFullString();
			t_rep.is_quant_or_ord = u.unitAttribs.quantOrOrd();
			t_rep.is_equals_compared = u.unitAttribs.isEqualityChecked();
			rep_names[u.seaview_rep_id] = t_rep;
			
			for (Unit u1: reps.get(u))
			{			
				String description = u1.toVeryShortString();
				TMP t = new TMP();
				t.name = description;
				t.long_name = u1.toFullString();
				unit_names[u1.seaview_id] = t;

				rep_id_map.put(u1.seaview_id, u.seaview_rep_id);
			}
		}
		
		System.out.println (rep_names.length + " rep names, " + unit_names.length + " unit names, " + rep_id_map.size() + " mappings");
		PrintWriter pw = new PrintWriter (new OutputStreamWriter(new FileOutputStream(filename + ".js"), "UTF-8"));
		pw.println ("var reps = " + new Gson().toJson(rep_names) + ";");
		//pw.println ("var unit_names = " + new Gson().toJson(unit_names) + ";");
		//pw.println ("var unit_mappings = " + new Gson().toJson(rep_id_map) + ";");
		pw.close();
	}
	
	public static void main (String args[]) throws IOException, TargetLostException
	{
		Analyze.main(args);
		Unit._current_unit_collection.assign_seaview_ids();
		for (String arg: args)
		{
			if (arg.endsWith(".jar"))
				instrumentJar (arg);
		}
		
		String f = TMP_DIR + File.separator + "ips";
		new File(TMP_DIR).mkdirs();
		IPInfo.commit(f);
		write_units(TMP_DIR + File.separator + "units");
	}
}
