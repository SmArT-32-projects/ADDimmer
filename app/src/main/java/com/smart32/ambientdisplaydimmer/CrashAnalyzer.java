package com.smart32.ambientdisplaydimmer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class CrashAnalyzer {
    private static final String TAG = "[ADDimmer Analyzer] ";
    private static final int MAX_DUMP_CLASSES = 250; // Safeguard against logcat flooding

    // Caches to prevent logcat flooding
    private static final Set<String> sDumpedClasses = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> sScannedPackages = Collections.synchronizedSet(new HashSet<>());

    // Analyze missing methods/fields
    public static void analyzeAndLog(Throwable t, Class<?> targetClass, String contextInfo) {
        XposedBridge.log(TAG + "Error in [" + contextInfo + "]: " + t.toString());

        if (targetClass == null) {
            XposedBridge.log(TAG + "Target class is null. Cannot dump structure.");
            return;
        }

        if (t instanceof NoSuchMethodError || t instanceof NoSuchFieldError || t instanceof NoSuchMethodException || t instanceof NoSuchFieldException) {
            String className = targetClass.getName();

            // Atomic check and add
            if (!sDumpedClasses.add(className)) {
                XposedBridge.log(TAG + "Class structure for [" + className + "] already dumped. Skipping.");
                return;
            }

            XposedBridge.log(TAG + "=== DUMPING CLASS STRUCTURE: " + className + " ===");
            dumpFields(targetClass);
            dumpMethods(targetClass);
            XposedBridge.log(TAG + "=== END OF DUMP ===");
        }
    }

    // Analyze missing classes
    public static void analyzeClassNotFound(Throwable t, ClassLoader classLoader, String expectedClassName, String contextInfo) {
        XposedBridge.log(TAG + "ClassNotFound Error in [" + contextInfo + "]: " + t.toString());

        if (classLoader == null || expectedClassName == null) {
            XposedBridge.log(TAG + "ClassLoader or ClassName is null. Cannot proceed with analysis.");
            return;
        }

        // Extract target package for scanning
        int lastDotIndex = expectedClassName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            XposedBridge.log(TAG + "Invalid class name format: " + expectedClassName);
            return;
        }
        String targetPackage = expectedClassName.substring(0, lastDotIndex);

        // Atomic check and add
        if (!sScannedPackages.add(targetPackage)) {
            XposedBridge.log(TAG + "Package [" + targetPackage + "] already scanned. Skipping DEX analysis.");
            return;
        }

        // Dump ClassLoader hierarchy to identify custom OEM loaders
        dumpClassLoaderHierarchy(classLoader);

        XposedBridge.log(TAG + "=== SCANNING DEX FOR PACKAGE: " + targetPackage + " ===");
        try {
            // Risky operation: Attempting to access ART internal structures.
            // If it fails, it's safely caught below without breaking the module further.
            scanDexFiles(classLoader, targetPackage);
        } catch (Throwable scanError) {
            XposedBridge.log(TAG + "Dynamic dex scanning failed (OEM restricted or changed ART internals): " + scanError.getMessage());
        }
        XposedBridge.log(TAG + "=== END OF DEX SCAN ===");
    }

    private static void dumpClassLoaderHierarchy(ClassLoader cl) {
        XposedBridge.log(TAG + "--- ClassLoader Hierarchy ---");
        ClassLoader current = cl;
        while (current != null) {
            XposedBridge.log(TAG + "  " + current.getClass().getName());
            current = current.getParent();
        }
    }

    private static void scanDexFiles(ClassLoader classLoader, String targetPackage) throws Throwable {
        Object pathList = XposedHelpers.getObjectField(classLoader, "pathList");
        Object[] dexElements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");

        int matchCount = 0;
        boolean limitReached = false;

        for (Object element : dexElements) {
            if (limitReached) break;

            Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
            if (dexFile != null) {
                // Try to get the path of the currently scanned Dex/APK file
                String dexName = "Unknown path";
                try {
                    dexName = (String) XposedHelpers.callMethod(dexFile, "getName");
                } catch (Throwable ignored) { }

                XposedBridge.log(TAG + "Scanning source: " + dexName);

                @SuppressWarnings("unchecked")
                Enumeration<String> entries = (Enumeration<String>) XposedHelpers.callMethod(dexFile, "entries");

                while (entries.hasMoreElements()) {
                    String className = entries.nextElement();

                    if (className.startsWith(targetPackage)) {
                        XposedBridge.log(TAG + "  Found: " + className);
                        matchCount++;

                        // Limit the output to prevent huge logs
                        if (matchCount >= MAX_DUMP_CLASSES) {
                            XposedBridge.log(TAG + "  [WARNING] Max class limit (" + MAX_DUMP_CLASSES + ") reached. Truncating output.");
                            limitReached = true;
                            break;
                        }
                    }
                }
            }
        }

        if (matchCount == 0) {
            XposedBridge.log(TAG + "  (No classes found matching the package. Package might be missing or heavily renamed)");
        }
    }

    private static void dumpFields(Class<?> clazz) {
        XposedBridge.log(TAG + "--- FIELDS ---");
        Field[] fields = clazz.getDeclaredFields();
        if (fields.length == 0) XposedBridge.log(TAG + "  (No fields found)");
        for (Field field : fields) {
            XposedBridge.log(TAG + "  " + Modifier.toString(field.getModifiers()) + " " + field.getType().getSimpleName() + " " + field.getName());
        }
    }

    private static void dumpMethods(Class<?> clazz) {
        XposedBridge.log(TAG + "--- METHODS ---");
        Method[] methods = clazz.getDeclaredMethods();
        if (methods.length == 0) XposedBridge.log(TAG + "  (No methods found)");
        for (Method method : methods) {
            StringBuilder params = new StringBuilder();
            for (Class<?> pType : method.getParameterTypes()) {
                if (params.length() > 0) params.append(", ");
                params.append(pType.getSimpleName());
            }
            XposedBridge.log(TAG + "  " + Modifier.toString(method.getModifiers()) + " " + method.getReturnType().getSimpleName() + " " + method.getName() + "(" + params.toString() + ")");
        }
    }
}
