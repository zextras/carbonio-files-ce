package com.zextras.carbonio.files.messageBroker;

import java.util.function.Supplier;

public class Utils {

  public static boolean executeWithRetry(int maxRetries, Supplier<Boolean> operation) throws InterruptedException {
    int retries = 0;
    while (retries < maxRetries) {
      boolean result = operation.get();
      if(result) return true;
      else{
        Thread.sleep(5000);
        retries++;
      }
    }
    return false;
  }
}
