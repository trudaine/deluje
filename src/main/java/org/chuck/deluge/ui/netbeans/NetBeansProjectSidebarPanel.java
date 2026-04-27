package org.chuck.deluge.ui.netbeans;

/** NetBeans-compatible Sidebar Panel containing Library, Editor, etc. */
public class NetBeansProjectSidebarPanel extends javax.swing.JPanel {
  private MainViewModel viewModel;

  private NetBeansLibraryPanel libraryPanel;
  private NetBeansEditorPanel editorPanel;
  private LibraryViewModel libraryVM;
  private EditorViewModel editorVM;

  public NetBeansProjectSidebarPanel() {
    initComponents();
    setupTabs();
  }

  private void setupTabs() {
    libraryVM = new LibraryViewModel();
    libraryPanel = new NetBeansLibraryPanel();
    tabbedPane.addTab("LIBRARY", libraryPanel);

    editorVM = new EditorViewModel();
    editorPanel = new NetBeansEditorPanel();
    tabbedPane.addTab("EDITOR", editorPanel);

    tabbedPane.addTab("MIDI", new javax.swing.JPanel());
    tabbedPane.addTab("SCRIPT", new javax.swing.JPanel());
  }

  public void setViewModel(MainViewModel viewModel) {
    this.viewModel = viewModel;
    if (libraryPanel != null) libraryPanel.setViewModel(viewModel);
    if (editorPanel != null) editorPanel.setViewModel(viewModel);
  }

  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    tabbedPane = new javax.swing.JTabbedPane();

    setBackground(new java.awt.Color(37, 37, 37));
    setLayout(new java.awt.BorderLayout());

    tabbedPane.setBackground(new java.awt.Color(45, 45, 45));
    tabbedPane.setForeground(new java.awt.Color(220, 220, 220));
    add(tabbedPane, java.awt.BorderLayout.CENTER);
  } // </editor-fold>//GEN-END:initComponents

  // Variables declaration - do not modify
  private javax.swing.JTabbedPane tabbedPane;
  // End of variables declaration
}
