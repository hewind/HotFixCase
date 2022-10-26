package com.enjoy.vm;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;

public class NewClassLoaderInjector {


    private static final class DispatchClassLoader extends ClassLoader {
        private final String mApplicationClassName;
        private final ClassLoader mOldClassLoader;

        private ClassLoader mNewClassLoader;

        private final ThreadLocal<Boolean> mCallFindClassOfLeafDirectly = new ThreadLocal<Boolean>() {
            @Override
            protected Boolean initialValue() {
                return false;
            }
        };

        DispatchClassLoader(String applicationClassName, ClassLoader oldClassLoader) {
            super(ClassLoader.getSystemClassLoader());
            mApplicationClassName = applicationClassName;
            mOldClassLoader = oldClassLoader;
        }

        void setNewClassLoader(ClassLoader classLoader) {
            mNewClassLoader = classLoader;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            System.out.println("find:" + name);
            if (mCallFindClassOfLeafDirectly.get()) {
                return null;
            }
            // 1、Application类不需要修复，使用原本的类加载器获得
            if (name.equals(mApplicationClassName)) {
                return findClass(mOldClassLoader, name);
            }
            // 2、加载热修复框架的类 因为不需要修复，就用原本的类加载器获得
            if (name.startsWith("com.enjoy.patch.")) {
                return findClass(mOldClassLoader, name);
            }

            try {
                return findClass(mNewClassLoader, name);
            } catch (ClassNotFoundException ignored) {
                return findClass(mOldClassLoader, name);
            }
        }

        private Class<?> findClass(ClassLoader classLoader, String name) throws ClassNotFoundException {
            try {
                //双亲委托，所以可能会stackoverflow死循环，防止这个情况
                mCallFindClassOfLeafDirectly.set(true);
                return classLoader.loadClass(name);
            } finally {
                mCallFindClassOfLeafDirectly.set(false);
            }
        }
    }

    public static ClassLoader inject(Application app, ClassLoader oldClassLoader,List<File> patchs) throws Throwable {
        // 分发加载任务的加载器，作为我们自己的加载器的父加载器
        DispatchClassLoader dispatchClassLoader = new DispatchClassLoader(app.getClass().getName(), oldClassLoader);
        //创建我们自己的加载器
        ClassLoader newClassLoader = createNewClassLoader(app, oldClassLoader, dispatchClassLoader,patchs);
        dispatchClassLoader.setNewClassLoader(newClassLoader);
        doInject(app, newClassLoader);
        return newClassLoader;
    }

    /**
     * 创建自己的类加载器
     * @param context
     * @param oldClassLoader
     * @param dispatchClassLoader
     * @param patchs
     * @return
     * @throws Throwable
     */
    private static ClassLoader createNewClassLoader(Context context, ClassLoader oldClassLoader, ClassLoader dispatchClassLoader,List<File> patchs) throws Throwable {
        //获取原来的PathClassLoader的属性变量pathList
        Field pathListField = ShareReflectUtil.findField(oldClassLoader, "pathList");
        Object oldPathList = pathListField.get(oldClassLoader);

        //从DexPathList上得到属性dexElements数组，dexElements数组类型为Element
        Field dexElementsField = ShareReflectUtil.findField(oldPathList, "dexElements");
        Object[] oldDexElements = (Object[]) dexElementsField.get(oldPathList);

        //从Element上得到属性dexFile
        Field dexFileFirst = ShareReflectUtil.findField(oldDexElements[0], "dexFile");

        //获得原始的dexPath，跟热修复的dexPath拼接，组成一个新的dexPath用于构造classloader
        //1、创建一个新的dexPath路径，而且热修复的dex路径必须放在第一个。
        StringBuilder newDexPathBuilder = new StringBuilder();
        String packageName = context.getPackageName();
        boolean isFirstItem = true;
        //patchs数组中存放的是热修复的dex路径
        for (File patch : patchs) {
            if (isFirstItem) {
                isFirstItem = false;
            } else {
                newDexPathBuilder.append(File.pathSeparator);
            }
            newDexPathBuilder.append(patch.getAbsolutePath());
        }
        //2、获取原来的dexPath，拼接到热修复dexPath之后，这样热修复dexPath和原来的dexPath组成一个新的newDexPath
        for (Object oldDexElement : oldDexElements) {
            String dexPath = null;
            DexFile dexFile = (DexFile) dexFileFirst.get(oldDexElement);
            if (dexFile != null) {
                dexPath = dexFile.getName();
            }
            if (dexPath == null || dexPath.isEmpty()) {
                continue;
            }
            if (!dexPath.contains("/" + packageName)) {
                continue;
            }
            if (isFirstItem) {
                isFirstItem = false;
            } else {
                newDexPathBuilder.append(File.pathSeparator);
            }
            newDexPathBuilder.append(dexPath);
        }
        //3、得到新的dexPath
        final String newDexPath = newDexPathBuilder.toString();

        //  app的native库（so） 文件目录 用于构造classloader
        Field nativeLibraryDirectoriesField = ShareReflectUtil.findField(oldPathList, "nativeLibraryDirectories");
        List<File> oldNativeLibraryDirectories = (List<File>) nativeLibraryDirectoriesField.get(oldPathList);

        //4、获取so文件加载路径
        StringBuilder libraryPathBuilder = new StringBuilder();
        isFirstItem = true;
        for (File libDir : oldNativeLibraryDirectories) {
            if (libDir == null) {
                continue;
            }
            if (isFirstItem) {
                isFirstItem = false;
            } else {
                libraryPathBuilder.append(File.pathSeparator);
            }
            libraryPathBuilder.append(libDir.getAbsolutePath());
        }

        String combinedLibraryPath = libraryPathBuilder.toString();

        //5、创建自己的类加载器
        ClassLoader result = new PathClassLoader(newDexPath, combinedLibraryPath, dispatchClassLoader);
        //6、修改原oldPathList的属性definingContext（definingContext的类型为ClassLoader）为自己创建的 PathClassLoader
        ShareReflectUtil.findField(oldPathList, "definingContext").set(oldPathList, result);
        //7、修改新创建的PathClassLoader的父加载器为dispatchClassLoader
        ShareReflectUtil.findField(result, "parent").set(result, dispatchClassLoader);
        return result;
    }


    private static void doInject(Application app, ClassLoader classLoader) throws Throwable {
        Thread.currentThread().setContextClassLoader(classLoader);

        //获取当前Application的mBase属性
        Context baseContext = (Context) ShareReflectUtil.findField(app, "mBase").get(app);
        //获取mBase中的mPackageInfo属性
        Object basePackageInfo = ShareReflectUtil.findField(baseContext, "mPackageInfo").get(baseContext);
        //修改mPackageInfo中的mClassLoader属性变量为自己的classLoader
        ShareReflectUtil.findField(basePackageInfo, "mClassLoader").set(basePackageInfo, classLoader);

        if (Build.VERSION.SDK_INT < 27) {
            Resources res = app.getResources();
            try {
                ShareReflectUtil.findField(res, "mClassLoader").set(res, classLoader);

                final Object drawableInflater = ShareReflectUtil.findField(res, "mDrawableInflater").get(res);
                if (drawableInflater != null) {
                    ShareReflectUtil.findField(drawableInflater, "mClassLoader").set(drawableInflater, classLoader);
                }
            } catch (Throwable ignored) {
                // Ignored.
            }
        }
    }

}

