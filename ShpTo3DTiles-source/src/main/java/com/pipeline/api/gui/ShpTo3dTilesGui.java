package com.pipeline.api.gui;

import com.pipeline.api.cli.ShpTo3dTilesCli;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop wrapper for the SHP -> GLB -> 3D Tiles conversion pipeline.
 *
 * Run from the packaged jar:
 *   java "-Dloader.main=com.pipeline.api.gui.ShpTo3dTilesGui" -jar pipeline-api-1.0.0.jar
 *
 * lon/lat/origin/height are no longer user inputs: they are derived from the
 * source CRS (optional explicit .prj, else the SHP companion .prj) and the
 * footprint centroid mean.
 */
public final class ShpTo3dTilesGui {

    private final JFrame frame = new JFrame("SHP -> 3D Tiles");

    // Required input
    private final JTextField shpField = new JTextField();
    private final JTextField outField = new JTextField();

    // Optional explicit .prj (blank = auto-detect SHP companion .prj)
    private final JTextField prjField = new JTextField();

    // Generation switches
    private final JCheckBox texturesBox        = new JCheckBox("生成外立面纹理", false);
    private final JCheckBox keepIntermediateBox = new JCheckBox("保留 OBJ / GLB 中间文件", false);

    // Parapet knobs (defaults mirror BuildingExtruder.Profile)
    private final JTextField parapetMinLc       = new JTextField("5");
    private final JTextField parapetMinHeight   = new JTextField("0.80");
    private final JTextField parapetMaxHeight   = new JTextField("1.40");
    private final JTextField parapetPerFloor    = new JTextField("0.04");
    private final JTextField parapetThickness   = new JTextField("0.40");

    // Rooftop machine-room knobs (single shaft)
    private final JTextField shaftMinLc         = new JTextField("9");
    private final JTextField shaftInset         = new JTextField("1.50");
    private final JTextField shaftClearance     = new JTextField("2.00");
    private final JTextField shaftMaxLen        = new JTextField("8.00");
    private final JTextField shaftMaxWid        = new JTextField("6.00");
    private final JTextField shaftMaxHeight     = new JTextField("6.00");
    private final JTextField shaftCenterBoxRatio = new JTextField("0.80");

    private final JButton  convertButton = new JButton("开始转换");
    private final JTextArea logArea      = new JTextArea();

    private ShpTo3dTilesGui() {
        buildUi();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ShpTo3dTilesGui().show());
    }

    private void buildUi() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(820, 760);
        frame.setMinimumSize(new java.awt.Dimension(720, 620));
        frame.setLocationRelativeTo(null);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(buildForm(), BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("运行日志"));
        content.add(logScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearButton = new JButton("清空日志");
        clearButton.addActionListener(event -> logArea.setText(""));
        convertButton.addActionListener(event -> startConversion());
        actions.add(clearButton);
        actions.add(convertButton);
        content.add(actions, BorderLayout.SOUTH);

        frame.setContentPane(content);
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;

        // ---- required inputs ----
        addFileRow(form, c, 0,  "输入 SHP",           shpField, false, null, null);
        addFileRow(form, c, 1,  "输出目录",           outField, true,  null, null);
        addFileRow(form, c, 2,  "PRJ 坐标系 (可选)",  prjField, false, "prj", "PRJ 坐标系文件 (*.prj)");

        // ---- generation switches ----
        c.gridx = 0; c.gridy = 3; c.gridwidth = 4;
        JPanel checks = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        checks.add(texturesBox);
        checks.add(keepIntermediateBox);
        form.add(checks, c);

        // ---- parapet panel ----
        c.gridwidth = 4;
        c.gridx = 0; c.gridy = 4;
        JPanel parapet = new JPanel(new GridBagLayout());
        parapet.setBorder(BorderFactory.createTitledBorder("女儿墙参数"));
        GridBagConstraints pc = new GridBagConstraints();
        pc.insets = new Insets(2, 4, 2, 4);
        pc.fill = GridBagConstraints.HORIZONTAL;
        pc.weightx = 0;
        addFieldRow(parapet, pc, 0, "起始层数 lc ≥",    parapetMinLc);
        addFieldRow(parapet, pc, 1, "最小高度 (m)",     parapetMinHeight);
        addFieldRow(parapet, pc, 2, "最大高度 (m)",     parapetMaxHeight);
        addFieldRow(parapet, pc, 3, "每层增量 (m)",     parapetPerFloor);
        addFieldRow(parapet, pc, 4, "墙体厚度 (m)",     parapetThickness);
        form.add(parapet, c);

        // ---- shaft panel ----
        c.gridx = 0; c.gridy = 5;
        JPanel shaft = new JPanel(new GridBagLayout());
        shaft.setBorder(BorderFactory.createTitledBorder("屋顶机房参数 (单机房)"));
        GridBagConstraints sc = new GridBagConstraints();
        sc.insets = new Insets(2, 4, 2, 4);
        sc.fill = GridBagConstraints.HORIZONTAL;
        sc.weightx = 0;
        addFieldRow(shaft, sc, 0, "起始层数 lc ≥",     shaftMinLc);
        addFieldRow(shaft, sc, 1, "屋面内缩 (m)",     shaftInset);
        addFieldRow(shaft, sc, 2, "距边界净距 (m)",   shaftClearance);
        addFieldRow(shaft, sc, 3, "最大长边 (m)",     shaftMaxLen);
        addFieldRow(shaft, sc, 4, "最大短边 (m)",     shaftMaxWid);
        addFieldRow(shaft, sc, 5, "最大高度 (m)",     shaftMaxHeight);
        addFieldRow(shaft, sc, 6, "中心采样比例",     shaftCenterBoxRatio);
        form.add(shaft, c);

        return form;
    }

    private void addFieldRow(JPanel panel, GridBagConstraints c, int row,
                             String label, JTextField field) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    private void addFileRow(JPanel panel, GridBagConstraints c, int row, String label,
                            JTextField field, boolean directory,
                            String filterExt, String filterDesc) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        panel.add(field, c);
        JButton browse = new JButton("浏览...");
        browse.addActionListener(event ->
                choosePath(field, directory, filterExt, filterDesc));
        c.gridx = 3;
        c.gridwidth = 1;
        c.weightx = 0;
        panel.add(browse, c);
    }

    private void choosePath(JTextField target, boolean directory,
                            String filterExt, String filterDesc) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(directory ? "选择输出目录" : "选择文件");
        chooser.setFileSelectionMode(directory
                ? JFileChooser.DIRECTORIES_ONLY
                : JFileChooser.FILES_ONLY);
        if (!directory && filterExt != null) {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    filterDesc, filterExt));
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startConversion() {
        List<String> arguments;
        try {
            arguments = buildArguments();
        } catch (Exception ex) {
            showError(ex.getMessage());
            return;
        }

        convertButton.setEnabled(false);
        appendLog("开始转换...");
        appendLog("输入: " + shpField.getText().trim());
        appendLog("输出: " + outField.getText().trim());

        Thread worker = new Thread(() -> {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream guiOut = new PrintStream(new LogOutputStream(), true);
            try {
                System.setOut(guiOut);
                System.setErr(guiOut);
                ShpTo3dTilesCli.main(arguments.toArray(String[]::new));
                SwingUtilities.invokeLater(() -> {
                    appendLog("转换完成，tileset.json 已写入输出目录。");
                    JOptionPane.showMessageDialog(frame,
                            "转换完成\n" + Path.of(outField.getText().trim()).toAbsolutePath(),
                            "完成", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("转换失败: " + ex.getMessage());
                    showError(ex.getMessage());
                });
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
                guiOut.close();
                SwingUtilities.invokeLater(() -> convertButton.setEnabled(true));
            }
        }, "shp-to-3dtiles-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private List<String> buildArguments() {
        Path shp = Path.of(shpField.getText().trim());
        Path out = Path.of(outField.getText().trim());
        if (!Files.isRegularFile(shp) || !shp.toString().toLowerCase().endsWith(".shp")) {
            throw new IllegalArgumentException("请选择有效的 .shp 文件");
        }
        if (out.toString().isBlank()) {
            throw new IllegalArgumentException("请选择输出目录");
        }

        List<String> args = new ArrayList<>();
        add(args, "--shp", shp.toString());
        add(args, "--out", out.toString());

        String prjText = prjField.getText().trim();
        if (!prjText.isBlank()) {
            Path prj = Path.of(prjText);
            if (!Files.isRegularFile(prj)) {
                throw new IllegalArgumentException("PRJ 文件不存在: " + prjText);
            }
            add(args, "--prj", prj.toString());
        }

        add(args, "--parapet-min-lc",     integer(parapetMinLc,     "女儿墙起始层数"));
        add(args, "--parapet-min-height", number (parapetMinHeight, "女儿墙最小高度"));
        add(args, "--parapet-max-height", number (parapetMaxHeight, "女儿墙最大高度"));
        add(args, "--parapet-per-floor",  number (parapetPerFloor,  "女儿墙每层增量"));
        add(args, "--parapet-thickness",  number (parapetThickness, "女儿墙厚度"));

        add(args, "--shaft-min-lc",       integer(shaftMinLc,       "屋顶机房起始层数"));
        add(args, "--shaft-inset",        number (shaftInset,       "屋面内缩"));
        add(args, "--shaft-clearance",    number (shaftClearance,   "机房距边界净距"));
        add(args, "--shaft-max-len",      number (shaftMaxLen,      "机房最大长边"));
        add(args, "--shaft-max-wid",      number (shaftMaxWid,      "机房最大短边"));
        add(args, "--shaft-max-height",   number (shaftMaxHeight,   "机房最大高度"));
        add(args, "--shaft-center-ratio", number (shaftCenterBoxRatio, "中心采样比例"));

        add(args, "--textures",          Boolean.toString(texturesBox.isSelected()));
        add(args, "--keep-intermediate", Boolean.toString(keepIntermediateBox.isSelected()));
        return args;
    }

    private static void add(List<String> args, String key, String value) {
        args.add(key);
        args.add(value);
    }

    private static String number(JTextField field, String label) {
        String value = field.getText().trim();
        try {
            Double.parseDouble(value);
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " 必须是数字");
        }
    }

    private static String integer(JTextField field, String label) {
        String value = field.getText().trim();
        try {
            Integer.parseInt(value);
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " 必须是整数");
        }
    }

    private void appendLog(String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            logArea.append(message + System.lineSeparator());
        } else {
            SwingUtilities.invokeLater(() -> appendLog(message));
        }
    }

    private void showError(String message) {
        String text = message == null || message.isBlank() ? "未知错误" : message;
        appendLog(text);
        JOptionPane.showMessageDialog(frame, text, "错误", JOptionPane.ERROR_MESSAGE);
    }

    private final class LogOutputStream extends OutputStream {
        private final StringBuilder line = new StringBuilder();

        @Override
        public void write(int value) {
            if (value == '\n') {
                appendLog(line.toString());
                line.setLength(0);
            } else if (value != '\r') {
                line.append((char) value);
            }
        }

        @Override
        public void flush() {
            if (line.length() > 0) {
                appendLog(line.toString());
                line.setLength(0);
            }
        }
    }

    private void show() {
        frame.setVisible(true);
    }
}
