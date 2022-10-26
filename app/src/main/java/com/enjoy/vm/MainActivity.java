package com.enjoy.vm;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.lang.reflect.Method;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Demo.test();
    }


    private void getVMStackLoader() {
        try {
            Class<?> cls = Class.forName("dalvik.system.VMStack");
            Method getCallingClassLoader = cls.getMethod("getCallingClassLoader");
            getCallingClassLoader.setAccessible(true);
            Object invoke = getCallingClassLoader.invoke(null);
            Log.i(TAG, "getVMStackLoader: " + invoke);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

    
