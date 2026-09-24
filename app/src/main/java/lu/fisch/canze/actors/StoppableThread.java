package lu.fisch.canze.actors;

/**
 * Worker thread with a cooperative stop request.
 *
 * The flag is volatile: without it the worker may never observe tryToStop(). The thread is also
 * interrupted, so blocking dongle reads (which check the interrupt flag) end promptly.
 *
 * Created by robertfisch on 30.12.2016.
 */
public class StoppableThread extends Thread {

    private volatile boolean stopped = false;

    public StoppableThread(Runnable runnable) {
        super(runnable);
    }

    @Override
    public void start() {
        stopped = false;
        super.start();
    }

    public void tryToStop() {
        stopped = true;
        interrupt();
    }

    public boolean isStopped() {
        return stopped;
    }
}
