import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.prefs.Preferences;

public class TrollEditor extends JFrame {

    // ====== Core UI ======
    private final JTextArea editor = new JTextArea();
    private File currentFile = null;
    private boolean dirty = false;

    // ====== Font state (remembered across runs) ======
    private final Preferences prefs = Preferences.userNodeForPackage(TrollEditor.class);
    private String fontFamily = "JetBrains Mono"; // will fall back if unavailable
    private int fontSize = 20;                    // start big if you like; zoom/toolbar control this
    private int fontStyle = Font.PLAIN;

    public TrollEditor() {
        super("Troll Text Editor");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Load saved font prefs (before building toolbar so it can sync)
        fontFamily = prefs.get("fontFamily", fontFamily);
        fontSize   = prefs.getInt("fontSize", fontSize);
        fontStyle  = prefs.getInt("fontStyle", fontStyle);

        // Editor basics
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(editor);
        add(scroll, BorderLayout.CENTER);

        // Menus, toolbar, status
        setJMenuBar(buildMenuBar());
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildStatusBar(), BorderLayout.SOUTH);

        // Apply initial font based on state (after toolbar is created)
        applyEditorFont();

        // Zoom shortcuts (Ctrl/Cmd +, -, 0)
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        InputMap im = editor.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = editor.getActionMap();

        am.put("zoomIn", new AbstractAction(){ public void actionPerformed(ActionEvent e){ fontSize = Math.min(96, fontSize + 1); applyEditorFont(); }});
        am.put("zoomOut", new AbstractAction(){ public void actionPerformed(ActionEvent e){ fontSize = Math.max(6, fontSize - 1); applyEditorFont(); }});
        am.put("zoomReset", new AbstractAction(){ public void actionPerformed(ActionEvent e){ fontSize = 14; applyEditorFont(); }});

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, mask), "zoomIn");   // Ctrl+=
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS,   mask), "zoomIn");   // some layouts
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD,    mask), "zoomIn");   // numpad +
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS,  mask), "zoomOut");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, mask), "zoomOut");// numpad -
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0,      mask), "zoomReset");

        // Track edits → dirty state
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { setDirty(true); }
            public void removeUpdate(DocumentEvent e) { setDirty(true); }
            public void changedUpdate(DocumentEvent e) { setDirty(true); }
        });

        // Confirm on close
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (maybeSaveLoss()) dispose();
            }
        });
    }

    // ====== Font helpers ======
    private void applyEditorFont() {
        // Ensure family exists; fall back nicely
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        Set<String> famSet = new HashSet<>(Arrays.asList(families));
        if (!famSet.contains(fontFamily)) {
            if (famSet.contains("JetBrains Mono")) fontFamily = "JetBrains Mono";
            else if (famSet.contains("Fira Code")) fontFamily = "Fira Code";
            else if (famSet.contains("Consolas"))  fontFamily = "Consolas";
            else if (famSet.contains("Menlo"))     fontFamily = "Menlo";
            else fontFamily = UIManager.getFont("TextArea.font").getFamily();
        }

        Font f = new Font(fontFamily, fontStyle, fontSize);
        editor.setFont(f);

        // Persist prefs
        prefs.put("fontFamily", fontFamily);
        prefs.putInt("fontSize", fontSize);
        prefs.putInt("fontStyle", fontStyle);
    }

    // ====== Menus ======
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        // File
        JMenu file = new JMenu("File");
        file.setMnemonic('F');

        JMenuItem newItem = new JMenuItem("New");
        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        newItem.addActionListener(e -> doNew());

        JMenuItem openItem = new JMenuItem("Open…");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        openItem.addActionListener(e -> doOpen());

        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        saveItem.addActionListener(e -> doSave(false));

        JMenuItem saveAsItem = new JMenuItem("Save As…");
        saveAsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        saveAsItem.addActionListener(e -> doSave(true));

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> {
            if (maybeSaveLoss()) dispose();
        });

        file.add(newItem);
        file.add(openItem);
        file.addSeparator();
        file.add(saveItem);
        file.add(saveAsItem);
        file.addSeparator();
        file.add(exitItem);

        // Edit
        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');

        JMenuItem wrapItem = new JCheckBoxMenuItem("Word Wrap", true);
        wrapItem.addActionListener(e -> editor.setLineWrap(((JCheckBoxMenuItem) wrapItem).isSelected()));

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copyItem.addActionListener(e -> editor.copy());

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        pasteItem.addActionListener(e -> editor.paste());

        JMenuItem cutItem = new JMenuItem("Cut");
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        cutItem.addActionListener(e -> editor.cut());

        edit.add(wrapItem);
        edit.addSeparator();
        edit.add(copyItem);
        edit.add(pasteItem);
        edit.add(cutItem);

        // Help
        JMenu help = new JMenu("Help");
        JMenuItem why = new JMenuItem("Why won’t it save?");
        why.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Because this is a troll editor. To save, you must retype the ENTIRE contents\n" +
                        "in the verification box exactly—same letters, spaces, line breaks and all.",
                "hehe :)", JOptionPane.INFORMATION_MESSAGE));
        help.add(why);

        bar.add(file);
        bar.add(edit);
        bar.add(help);
        return bar;
    }

    // ====== Toolbar (font family/size + bold/italic + zoom) ======
    private JToolBar buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        // families
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();

        JComboBox<String> fontBox = new JComboBox<>(families);
        fontBox.setEditable(false);
        fontBox.setMaximumSize(new Dimension(280, 28));
        fontBox.setSelectedItem(fontFamily);
        fontBox.addActionListener(e -> {
            fontFamily = (String) fontBox.getSelectedItem();
            applyEditorFont();
        });

        // size
        SpinnerNumberModel sizeModel = new SpinnerNumberModel(fontSize, 6, 96, 1);
        JSpinner sizeSpinner = new JSpinner(sizeModel);
        ((JSpinner.DefaultEditor) sizeSpinner.getEditor()).getTextField().setColumns(3);
        sizeSpinner.addChangeListener(e -> {
            fontSize = (Integer) sizeSpinner.getValue();
            applyEditorFont();
        });

        // style toggles
        JToggleButton boldBtn = new JToggleButton("B");
        boldBtn.setToolTipText("Bold");
        boldBtn.setSelected((fontStyle & Font.BOLD) != 0);
        boldBtn.addActionListener(e -> {
            fontStyle = (boldBtn.isSelected() ? Font.BOLD : 0) |
                        ((fontStyle & Font.ITALIC) != 0 ? Font.ITALIC : 0);
            applyEditorFont();
        });

        JToggleButton italicBtn = new JToggleButton("I");
        italicBtn.setToolTipText("Italic");
        italicBtn.setSelected((fontStyle & Font.ITALIC) != 0);
        italicBtn.addActionListener(e -> {
            fontStyle = ((fontStyle & Font.BOLD) != 0 ? Font.BOLD : 0) |
                        (italicBtn.isSelected() ? Font.ITALIC : 0);
            applyEditorFont();
        });

        // zoom buttons
        JButton zoomIn = new JButton("A+");
        JButton zoomOut = new JButton("A-");
        JButton zoomReset = new JButton("A0");
        zoomIn.setToolTipText("Zoom In (Ctrl/Cmd +)");
        zoomOut.setToolTipText("Zoom Out (Ctrl/Cmd -)");
        zoomReset.setToolTipText("Reset Zoom (Ctrl/Cmd 0)");

        zoomIn.addActionListener(e -> { fontSize = Math.min(96, fontSize + 1); sizeModel.setValue(fontSize); applyEditorFont(); });
        zoomOut.addActionListener(e -> { fontSize = Math.max(6, fontSize - 1); sizeModel.setValue(fontSize); applyEditorFont(); });
        zoomReset.addActionListener(e -> { fontSize = 14; sizeModel.setValue(fontSize); applyEditorFont(); });

        tb.add(new JLabel("  Font: "));
        tb.add(fontBox);
        tb.add(new JLabel("  Size: "));
        tb.add(sizeSpinner);
        tb.addSeparator();
        tb.add(boldBtn);
        tb.add(italicBtn);
        tb.addSeparator();
        tb.add(zoomIn);
        tb.add(zoomOut);
        tb.add(zoomReset);

        return tb;
    }

    // ====== Status bar ======
    private JPanel buildStatusBar() {
        JLabel status = new JLabel(" Ready");
        Timer t = new Timer(700, e -> {
            String text = editor.getText();
            int chars = text.length();
            int lines = editor.getLineCount();
            Font ef = editor.getFont();
            status.setText(" " + (dirty ? "*" : "") + (currentFile == null ? "Untitled" : currentFile.getName())
                    + "    |    Lines: " + lines + "    Chars: " + chars
                    + "    |    Font: " + ef.getFamily() + " " + ef.getSize()
                    + (ef.isBold() ? " B" : "") + (ef.isItalic() ? " I" : ""));
        });
        t.setRepeats(true);
        t.start();

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        p.add(status, BorderLayout.WEST);
        return p;
    }

    private void setDirty(boolean d) {
        dirty = d;
        setTitle((dirty ? "*" : "") + (currentFile == null ? "Untitled" : currentFile.getName()) + " — Troll Text Editor");
    }

    private boolean maybeSaveLoss() {
        if (!dirty) return true;
        int opt = JOptionPane.showConfirmDialog(this,
                "You have unsaved changes. Do you want to save first?",
                "Unsaved Changes", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (opt == JOptionPane.CANCEL_OPTION || opt == JOptionPane.CLOSED_OPTION) return false;
        if (opt == JOptionPane.YES_OPTION) return doSave(false);
        return true; // NO
    }

    private void doNew() {
        if (!maybeSaveLoss()) return;
        editor.setText("");
        currentFile = null;
        setDirty(false);
    }

    private void doOpen() {
        if (!maybeSaveLoss()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Text files (*.txt)", "txt"));
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                String content = readAll(f);
                editor.setText(content);
                editor.setCaretPosition(0);
                currentFile = f;
                setDirty(false);
            } catch (IOException ex) {
                error("Failed to open file:\n" + ex.getMessage());
            }
        }
    }

    /**
     * Save. If saveAs==true, always prompt for a target. Otherwise, reuse currentFile if present.
     * Before writing, force the user to retype the exact content; only on exact match do we save.
     */
    private boolean doSave(boolean saveAs) {
        String original = editor.getText();

        // Build verification dialog
        JTextArea verifyArea = new JTextArea();
        verifyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        verifyArea.setLineWrap(false);
        verifyArea.setWrapStyleWord(false);

        // (Optional extra trolling: disable paste)
        ActionMap am = verifyArea.getActionMap();
        am.put(DefaultEditorKit.pasteAction, new AbstractAction(){ public void actionPerformed(ActionEvent e){} });
        verifyArea.setTransferHandler(new TransferHandler(null));

        JScrollPane sp = new JScrollPane(verifyArea);
        sp.setPreferredSize(new Dimension(700, 400));

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JLabel lbl = new JLabel("<html><b>Retype exactly what you want to save</b><br>" +
                "It must match the editor contents character-for-character (including newlines).</html>");
        panel.add(lbl, BorderLayout.NORTH);
        panel.add(sp, BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(this, panel, "Type it again 🙂",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (res != JOptionPane.OK_OPTION) {
            info("Save cancelled (you didn’t retype it).");
            return false;
        }

        String retyped = verifyArea.getText();

        // Exact compare
        if (!original.equals(retyped)) {
            int mismatch = firstMismatch(original, retyped);
            String msg = "Nope. Not an exact match.\n";
            if (mismatch >= 0) {
                int start = Math.max(0, mismatch - 10);
                int endO = Math.min(original.length(), mismatch + 10);
                int endR = Math.min(retyped.length(), mismatch + 10);
                String snippetO = original.substring(start, endO).replace("\n","\\n");
                String snippetR = retyped.substring(start, endR).replace("\n","\\n");
                msg += "First difference at index " + mismatch + "\n" +
                       "Editor:  …" + snippetO + "…\n" +
                       "Retyped: …" + snippetR + "…";
            }
            error(msg);
            return false;
        }

        // Choose file if needed
        File target = currentFile;
        if (saveAs || target == null) {
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(target != null ? target : new File("untitled.txt"));
            chooser.setFileFilter(new FileNameExtensionFilter("Text files (*.txt)", "txt"));
            int choice = chooser.showSaveDialog(this);
            if (choice != JFileChooser.APPROVE_OPTION) return false;
            target = chooser.getSelectedFile();
        }

        try {
            writeAll(target, original);
            currentFile = target;
            setDirty(false);
            info("Saved successfully to: " + target.getAbsolutePath());
            return true;
        } catch (IOException ex) {
            error("Failed to save file:\n" + ex.getMessage());
            return false;
        }
    }

    // ====== Utils ======
    private static int firstMismatch(String a, String b) {
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) if (a.charAt(i) != b.charAt(i)) return i;
        if (a.length() != b.length()) return len;
        return -1;
    }

    private static String readAll(File f) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void writeAll(File f, String s) throws IOException {
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Info", JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    // ====== Main ======
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TrollEditor().setVisible(true));
    }
}
