package app.morphe.extension.reddit.reports;

import android.app.Application;

import androidx.core.app.CoreComponentFactory;

public final class ReportComponentFactory extends CoreComponentFactory {
    @Override public Application instantiateApplication(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        // Reddit only initializes its dependency graph in its own processes. Its memory
        // callbacks dereference that graph even in processes where it was never initialized.
        if (Application.getProcessName().endsWith(":gigi_reports")) return new Application();
        return super.instantiateApplication(loader, name);
    }
}
