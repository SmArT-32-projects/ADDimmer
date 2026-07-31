package com.smart32.ambientdisplaydimmer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

public class CrashAnalyzer {
    private static final int MAX_DUMP_CLASSES = 250; // Safeguard against logcat flooding

    // Caches to prevent logcat flooding
    private static final Set<String> sDumpedClasses = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> sScannedPackages = Collections.synchronizedSet(new HashSet<>());

    // Analyze missing methods/fields
    public static void analyzeAndLog(Throwable t, Class<?> targetClass, String contextInfo) {
        AmbientDisplayOverride.logFatal("Error in [" + contextInfo + "]: " + t.toString());

        if (targetClass == null) {
            AmbientDisplayOverride.logError("Target class is null. Cannot dump structure.");
            return;
        }

        if (t instanceof NoSuchMethodError || t instanceof NoSuchFieldError || t instanceof NoSuchMethodException || t instanceof NoSuchFieldException) {
            String className = targetClass.getName();

            // Atomic check and add
            if (!sDumpedClasses.add(className)) {
                AmbientDisplayOverride.logInfo("Class structure for [" + className + "] already dumped. Skipping.");
                return;
            }

            AmbientDisplayOverride.logFatal("=== DUMPING CLASS STRUCTURE: " + className + " ===");
            dumpFields(targetClass);
            dumpMethods(targetClass);
            AmbientDisplayOverride.logFatal("=== END OF DUMP ===");
        }
    }

    // Analyze missing classes
    public static void analyzeClassNotFound(Throwable t, ClassLoader classLoader, String expectedClassName, String contextInfo) {
        AmbientDisplayOverride.logFatal("ClassNotFound Error in [" + contextInfo + "]: " + t.toString());

        if (classLoader == null || expectedClassName == null) {
            AmbientDisplayOverride.logError("ClassLoader or ClassName is null. Cannot proceed with analysis.");
            return;
        }

        // Extract target package for scanning
        int lastDotIndex = expectedClassName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            AmbientDisplayOverride.logError("Invalid class name format: " + expectedClassName);
            return;
        }
        String targetPackage = expectedClassName.substring(0, lastDotIndex);

        // Atomic check and add
        if (!sScannedPackages.add(targetPackage)) {
            AmbientDisplayOverride.logInfo("Package [" + targetPackage + "] already scanned. Skipping DEX analysis.");
            return;
        }

        // Dump ClassLoader hierarchy to identify custom OEM loaders
        dumpClassLoaderHierarchy(classLoader);

        AmbientDisplayOverride.logFatal("=== SCANNING DEX FOR PACKAGE: " + targetPackage + " ===");
        try {
            // Risky operation: Attempting to access ART internal structures.
            // If it fails, it's safely caught below without breaking the module further.
            scanDexFiles(classLoader, targetPackage);
        } catch (Throwable scanError) {
            AmbientDisplayOverride.logError("Dynamic dex scanning failed (OEM restricted or changed ART internals): " + scanError.getMessage());
        }
        AmbientDisplayOverride.logFatal("=== END OF DEX SCAN ===");
    }

    private static void dumpClassLoaderHierarchy(ClassLoader cl) {
        AmbientDisplayOverride.logFatal("--- ClassLoader Hierarchy ---");
        ClassLoader current = cl;
        while (current != null) {
            AmbientDisplayOverride.logFatal("  " + current.getClass().getName());
            current = current.getParent();
        }
    }

    private static void scanDexFiles(ClassLoader classLoader, String targetPackage) {
        Object pathList = null;
        ClassLoader currentClassLoader = classLoader;

        // Traverse the ClassLoader hierarchy to find the one holding 'pathList' (e.g., BaseDexClassLoader)
        while (currentClassLoader != null) {
            try {
                pathList = XposedHelpers.getObjectField(currentClassLoader, "pathList");
                if (pathList != null) break;
            } catch (Throwable ignored) {
                // Not in this ClassLoader, move to parent
                currentClassLoader = currentClassLoader.getParent();
            }
        }

        if (pathList == null) {
            AmbientDisplayOverride.logError("  Could not find 'pathList' in any ClassLoader in the hierarchy. Aborting dex scan.");
            return;
        }

        Object[] dexElements;
        try {
            dexElements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
        } catch (Throwable t) {
            AmbientDisplayOverride.logError("  Found pathList, but 'dexElements' is missing or inaccessible.");
            return;
        }

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

                AmbientDisplayOverride.logFatal("Scanning source: " + dexName);

                @SuppressWarnings("unchecked")
                Enumeration<String> entries = (Enumeration<String>) XposedHelpers.callMethod(dexFile, "entries");

                while (entries.hasMoreElements()) {
                    String className = entries.nextElement();

                    if (className.startsWith(targetPackage)) {
                        AmbientDisplayOverride.logFatal("  Found: " + className);
                        matchCount++;

                        // Limit the output to prevent huge logs
                        if (matchCount >= MAX_DUMP_CLASSES) {
                            AmbientDisplayOverride.logError("  Max class limit (" + MAX_DUMP_CLASSES + ") reached. Truncating output.");
                            limitReached = true;
                            break;
                        }
                    }
                }
            }
        }

        // Pass 2: Heuristic fallback search
        // Triggered if the exact package (e.g., com.android.systemui.doze) was heavily renamed or moved by OEM
        if (matchCount == 0) {
            AmbientDisplayOverride.logError("  Exact package not found. Attempting heuristic search up one level...");

            int lastDot = targetPackage.lastIndexOf('.');
            if (lastDot != -1) {
                // Extract parent package (e.g., "com.android.systemui") and keyword (e.g., "doze")
                String parentPackage = targetPackage.substring(0, lastDot);
                String keyword = targetPackage.substring(lastDot + 1).toLowerCase();

                AmbientDisplayOverride.logFatal("  --- Heuristic Search: classes in [" + parentPackage + "] containing [" + keyword + "] ---");

                // Reset flag for the second pass
                limitReached = false;

                // Re-iterate through dex elements to avoid storing massive lists in RAM
                for (Object element : dexElements) {
                    if (limitReached) break;

                    Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
                    if (dexFile != null) {
                        @SuppressWarnings("unchecked")
                        Enumeration<String> entries = (Enumeration<String>) XposedHelpers.callMethod(dexFile, "entries");

                        while (entries.hasMoreElements()) {
                            String className = entries.nextElement();

                            // Expanded check: Starts with parent package AND contains the keyword (case-insensitive)
                            if (className.startsWith(parentPackage) && className.toLowerCase().contains(keyword)) {
                                AmbientDisplayOverride.logFatal("  Found heuristic match: " + className);
                                matchCount++;

                                if (matchCount >= MAX_DUMP_CLASSES) {
                                    AmbientDisplayOverride.logError("  Max class limit reached during heuristic search.");
                                    limitReached = true;
                                    break;
                                }
                            }
                        }
                    }
                }

                if (matchCount == 0) {
                    AmbientDisplayOverride.logError("  No classes found even with heuristic search");
                }
            } else {
                AmbientDisplayOverride.logError("  Target package has no parent level. Cannot perform heuristic search");
            }
        }
    }

    private static void dumpFields(Class<?> clazz) {
        AmbientDisplayOverride.logFatal("--- FIELDS ---");
        Class<?> current = clazz;

        // Traverse up to Object.class to show inherited fields
        while (current != null && current != Object.class) {
            // Skip base Android/Java framework classes to reduce logcat noise
            if (current.getName().startsWith("android.") || current.getName().startsWith("java.")) {
                current = current.getSuperclass();
                continue;
            }

            AmbientDisplayOverride.logFatal("  [Declared in: " + current.getSimpleName() + "]");
            Field[] fields = current.getDeclaredFields();
            if (fields.length == 0) {
                AmbientDisplayOverride.logFatal("    (No fields found)");
            } else {
                for (Field field : fields) {
                    AmbientDisplayOverride.logFatal("    " + Modifier.toString(field.getModifiers()) + " " + field.getType().getSimpleName() + " " + field.getName());
                }
            }
            current = current.getSuperclass();
        }
    }

    private static void dumpMethods(Class<?> clazz) {
        AmbientDisplayOverride.logFatal("--- METHODS ---");
        Class<?> current = clazz;

        // Traverse up to Object.class to show inherited methods
        while (current != null && current != Object.class) {
            // Skip base Android/Java framework classes to reduce logcat noise
            if (current.getName().startsWith("android.") || current.getName().startsWith("java.")) {
                current = current.getSuperclass();
                continue;
            }

            AmbientDisplayOverride.logFatal("  [Declared in: " + current.getSimpleName() + "]");
            Method[] methods = current.getDeclaredMethods();
            if (methods.length == 0) {
                AmbientDisplayOverride.logFatal("    (No methods found)");
            } else {
                for (Method method : methods) {
                    StringBuilder params = new StringBuilder();
                    for (Class<?> pType : method.getParameterTypes()) {
                        if (params.length() > 0) params.append(", ");
                        params.append(pType.getSimpleName());
                    }
                    AmbientDisplayOverride.logFatal("    " + Modifier.toString(method.getModifiers()) + " " + method.getReturnType().getSimpleName() + " " + method.getName() + "(" + params.toString() + ")");
                }
            }
            current = current.getSuperclass();
        }
    }
}
