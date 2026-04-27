package org.chuck.deluge.ui.netbeans;

import javax.swing.tree.TreeModel;

/** ViewModel for the Library/Browser component. */
public class LibraryViewModel extends BaseViewModel {
  private TreeModel treeModel;

  public TreeModel getTreeModel() {
    return treeModel;
  }

  public void setTreeModel(TreeModel treeModel) {
    TreeModel old = this.treeModel;
    this.treeModel = treeModel;
    firePropertyChange("treeModel", old, treeModel);
  }
}
