package org.chuck.deluge.ui.netbeans;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

/** Base class for ViewModels with PropertyChangeSupport for Swing data binding. */
public abstract class BaseViewModel {
  protected final PropertyChangeSupport pcs = new PropertyChangeSupport(this);

  public void addPropertyChangeListener(PropertyChangeListener listener) {
    pcs.addPropertyChangeListener(listener);
  }

  public void removePropertyChangeListener(PropertyChangeListener listener) {
    pcs.removePropertyChangeListener(listener);
  }

  protected void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
    pcs.firePropertyChange(propertyName, oldValue, newValue);
  }
}
