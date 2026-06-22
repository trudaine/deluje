package org.deluge.hid;

/** Background thread to update the global blink states of the virtual LEDs. */
public class Flasher extends Thread {
  private static final Flasher INSTANCE = new Flasher();

  public static void startGlobal() {
    if (!INSTANCE.isAlive()) {
      INSTANCE.setDaemon(true);
      INSTANCE.start();
    }
  }

  @Override
  public void run() {
    while (true) {
      PadLEDs.updateFlashes(System.currentTimeMillis());
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        break;
      }
    }
  }
}
