package com.pipeline.api.gui;

import com.pipeline.api.cli.ShpTo3dTilesCli;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import javax.swing.filechooser.FileNameExtensionFilter;

public final class ShpTo3dTilesGui {
   private final JFrame frame = new JFrame("SHP -> 3D Tiles");
   private final JTextField shpField = new JTextField();
   private final JTextField outField = new JTextField();
   private final JTextField prjField = new JTextField();
   private final JCheckBox texturesBox = new JCheckBox("生成外立面纹理", true);
   private final JCheckBox buildingLodChainBox = new JCheckBox("启用三级 LOD（不勾选为最精细模型）", false);
   private final JTextField whiteTexture = new JTextField("", 22);
   private final JTextField layeredTexture = new JTextField("", 22);
   private final JCheckBox keepIntermediateBox = new JCheckBox("保留 OBJ / GLB 中间文件", false);
   private final JTextField parapetMinLc = new JTextField("5");
   private final JTextField parapetMinHeight = new JTextField("0.80");
   private final JTextField parapetMaxHeight = new JTextField("1.40");
   private final JTextField parapetPerFloor = new JTextField("0.04");
   private final JTextField parapetThickness = new JTextField("0.40");
   private final JTextField metadataFields = new JTextField("bldUseage,entityid,wkBldId", 28);
   private final JTextField floorLineThreshold = new JTextField("10");
   private final JTextField shaftMinLc = new JTextField("9");
   private final JTextField shaftInset = new JTextField("1.50");
   private final JTextField shaftClearance = new JTextField("2.00");
   private final JTextField shaftMaxLen = new JTextField("8.00");
   private final JTextField shaftMaxWid = new JTextField("6.00");
   private final JTextField shaftMaxHeight = new JTextField("6.00");
   private final JTextField shaftCenterBoxRatio = new JTextField("0.80");
   private final JButton convertButton = new JButton("开始转换");
   private final JTextArea logArea = new JTextArea();

   private ShpTo3dTilesGui() {
      this.buildUi();
   }

   public static void main(String[] var0) {
      SwingUtilities.invokeLater(() -> new ShpTo3dTilesGui().show());
   }

   private void chooseTexture(JTextField var1) {
      JFileChooser var2 = new JFileChooser();
      var2.setFileFilter(new FileNameExtensionFilter("JPEG / PNG", "jpg", "jpeg", "png"));
      if (var2.showOpenDialog(this.frame) == 0) {
         var1.setText(var2.getSelectedFile().getAbsolutePath());
      }
   }

   private void buildUi() {
      this.frame.setDefaultCloseOperation(3);
      this.frame.setSize(820, 760);
      this.frame.setMinimumSize(new Dimension(720, 620));
      this.frame.setLocationRelativeTo(null);
      JPanel var1 = new JPanel(new BorderLayout(10, 10));
      var1.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
      var1.add(this.buildForm(), "North");
      this.logArea.setEditable(false);
      this.logArea.setLineWrap(true);
      this.logArea.setWrapStyleWord(true);
      this.logArea.setFont(new Font("Monospaced", 0, 12));
      JScrollPane var2 = new JScrollPane(this.logArea);
      var2.setBorder(BorderFactory.createTitledBorder("运行日志"));
      var1.add(var2, "Center");
      JPanel var3 = new JPanel(new FlowLayout(2));
      JButton var4 = new JButton("清空日志");
      var4.addActionListener(var1x -> this.logArea.setText(""));
      this.convertButton.addActionListener(var1x -> this.startConversion());
      var3.add(var4);
      var3.add(this.convertButton);
      var1.add(var3, "South");
      this.frame.setContentPane(var1);
   }

   private JPanel buildForm() {
      JPanel var1 = new JPanel(new GridBagLayout());
      GridBagConstraints var2 = new GridBagConstraints();
      var2.insets = new Insets(4, 4, 4, 4);
      var2.fill = 2;
      var2.weightx = 0.0;
      this.addFileRow(var1, var2, 0, "输入 SHP", this.shpField, false, null, null);
      this.addFileRow(var1, var2, 1, "输出目录", this.outField, true, null, null);
      this.addFileRow(var1, var2, 2, "PRJ 坐标系 (可选)", this.prjField, false, "prj", "PRJ 坐标系文件 (*.prj)");
      var2.gridx = 0;
      var2.gridy = 3;
      var2.gridwidth = 4;
      JPanel var3 = new JPanel(new FlowLayout(0, 0, 0));
      var3.add(this.texturesBox);
      var3.add(this.buildingLodChainBox);
      var3.add(this.keepIntermediateBox);
      var3.add(new JLabel("白模纹理"));
      var3.add(this.whiteTexture);
      var3.add(new JLabel("分层纹理"));
      var3.add(this.layeredTexture);
      JButton var4 = new JButton("Browse white");
      var4.addActionListener(var1x -> this.chooseTexture(this.whiteTexture));
      JButton var5 = new JButton("Browse layered");
      var5.addActionListener(var1x -> this.chooseTexture(this.layeredTexture));
      var3.add(var4);
      var3.add(var5);
      var3.setLayout(new GridLayout(0, 2, 6, 4));
      var1.add(var3, var2);
      var2.gridwidth = 4;
      var2.gridx = 0;
      var2.gridy = 4;
      JPanel var6 = new JPanel(new GridBagLayout());
      var6.setBorder(BorderFactory.createTitledBorder("女儿墙参数"));
      GridBagConstraints var7 = new GridBagConstraints();
      var7.insets = new Insets(2, 4, 2, 4);
      var7.fill = 2;
      var7.weightx = 0.0;
      this.addFieldRow(var6, var7, 0, "起始层数 lc ≥", this.parapetMinLc);
      this.addFieldRow(var6, var7, 1, "最小高度 (m)", this.parapetMinHeight);
      this.addFieldRow(var6, var7, 2, "最大高度 (m)", this.parapetMaxHeight);
      this.addFieldRow(var6, var7, 3, "每层增量 (m)", this.parapetPerFloor);
      this.addFieldRow(var6, var7, 4, "墙体厚度 (m)", this.parapetThickness);
      this.addFieldRow(var6, var7, 5, "分层线：LC 大于此值", this.floorLineThreshold);
      this.addFieldRow(var6, var7, 6, "输出属性字段（逗号分隔）", this.metadataFields);
      var1.add(var6, var2);
      var2.gridx = 0;
      var2.gridy = 5;
      JPanel var8 = new JPanel(new GridBagLayout());
      var8.setBorder(BorderFactory.createTitledBorder("屋顶机房参数 (单机房)"));
      GridBagConstraints var9 = new GridBagConstraints();
      var9.insets = new Insets(2, 4, 2, 4);
      var9.fill = 2;
      var9.weightx = 0.0;
      this.addFieldRow(var8, var9, 0, "起始层数 lc ≥", this.shaftMinLc);
      this.addFieldRow(var8, var9, 1, "屋面内缩 (m)", this.shaftInset);
      this.addFieldRow(var8, var9, 2, "距边界净距 (m)", this.shaftClearance);
      this.addFieldRow(var8, var9, 3, "最大长边 (m)", this.shaftMaxLen);
      this.addFieldRow(var8, var9, 4, "最大短边 (m)", this.shaftMaxWid);
      this.addFieldRow(var8, var9, 5, "最大高度 (m)", this.shaftMaxHeight);
      this.addFieldRow(var8, var9, 6, "中心采样比例", this.shaftCenterBoxRatio);
      var1.add(var8, var2);
      return var1;
   }

   private void addFieldRow(JPanel var1, GridBagConstraints var2, int var3, String var4, JTextField var5) {
      var2.gridy = var3;
      var2.gridx = 0;
      var2.gridwidth = 1;
      var2.weightx = 0.0;
      var1.add(new JLabel(var4), var2);
      var2.gridx = 1;
      var2.gridwidth = 1;
      var2.weightx = 1.0;
      var1.add(var5, var2);
   }

   private void addFileRow(JPanel var1, GridBagConstraints var2, int var3, String var4, JTextField var5, boolean var6, String var7, String var8) {
      var2.gridy = var3;
      var2.gridx = 0;
      var2.gridwidth = 1;
      var2.weightx = 0.0;
      var1.add(new JLabel(var4), var2);
      var2.gridx = 1;
      var2.gridwidth = 2;
      var2.weightx = 1.0;
      var1.add(var5, var2);
      JButton var9 = new JButton("浏览...");
      var9.addActionListener(var5x -> this.choosePath(var5, var6, var7, var8));
      var2.gridx = 3;
      var2.gridwidth = 1;
      var2.weightx = 0.0;
      var1.add(var9, var2);
   }

   private void choosePath(JTextField var1, boolean var2, String var3, String var4) {
      JFileChooser var5 = new JFileChooser();
      var5.setDialogTitle(var2 ? "选择输出目录" : "选择文件");
      var5.setFileSelectionMode(var2 ? 1 : 0);
      if (!var2 && var3 != null) {
         var5.setFileFilter(new FileNameExtensionFilter(var4, var3));
      }

      if (var5.showOpenDialog(this.frame) == 0) {
         var1.setText(var5.getSelectedFile().getAbsolutePath());
      }
   }

   private void startConversion() {
      List<String> var1;
      try {
         var1 = this.buildArguments();
      } catch (Exception var3) {
         this.showError(var3.getMessage());
         return;
      }

      this.convertButton.setEnabled(false);
      this.appendLog("开始转换...");
      this.appendLog("输入: " + this.shpField.getText().trim());
      this.appendLog("输出: " + this.outField.getText().trim());
      Thread var2 = new Thread(() -> {
         PrintStream var2x = System.out;
         PrintStream var3x = System.err;
         PrintStream var4 = new PrintStream(new ShpTo3dTilesGui.LogOutputStream(), true);

         try {
            System.setOut(var4);
            System.setErr(var4);
            ShpTo3dTilesCli.main(var1.toArray(String[]::new));
            SwingUtilities.invokeLater(() -> {
               this.appendLog("转换完成，tileset.json 已写入输出目录。");
               JOptionPane.showMessageDialog(this.frame, "转换完成\n" + Path.of(this.outField.getText().trim()).toAbsolutePath(), "完成", 1);
            });
         } catch (Exception var9) {
            SwingUtilities.invokeLater(() -> {
               this.appendLog("转换失败: " + var9.getMessage());
               this.showError(var9.getMessage());
            });
         } finally {
            System.setOut(var2x);
            System.setErr(var3x);
            var4.close();
            SwingUtilities.invokeLater(() -> this.convertButton.setEnabled(true));
         }
      }, "shp-to-3dtiles-worker");
      var2.setDaemon(true);
      var2.start();
   }

   private List<String> buildArguments() {
      Path var1 = Path.of(this.shpField.getText().trim());
      Path var2 = Path.of(this.outField.getText().trim());
      if (!Files.isRegularFile(var1) || !var1.toString().toLowerCase().endsWith(".shp")) {
         throw new IllegalArgumentException("请选择有效的 .shp 文件");
      }

      if (var2.toString().isBlank()) {
         throw new IllegalArgumentException("请选择输出目录");
      }

      ArrayList var3 = new ArrayList();
      add(var3, "--shp", var1.toString());
      add(var3, "--out", var2.toString());
      String var4 = this.prjField.getText().trim();
      if (!var4.isBlank()) {
         Path var5 = Path.of(var4);
         if (!Files.isRegularFile(var5)) {
            throw new IllegalArgumentException("PRJ 文件不存在: " + var4);
         }

         add(var3, "--prj", var5.toString());
      }

      add(var3, "--floor-line-threshold", integer(this.floorLineThreshold, "分层线阈值"));
      var3.add("--metadata-fields");
      var3.add(this.metadataFields.getText().trim());
      add(var3, "--white-texture", this.whiteTexture.getText().isBlank() ? "db_sumo_bui_001" : this.whiteTexture.getText().trim());
      add(var3, "--layered-texture", this.layeredTexture.getText().isBlank() ? "dise_G1_dx_02" : this.layeredTexture.getText().trim());
      add(var3, "--parapet-min-lc", integer(this.parapetMinLc, "女儿墙起始层数"));
      add(var3, "--parapet-min-height", number(this.parapetMinHeight, "女儿墙最小高度"));
      add(var3, "--parapet-max-height", number(this.parapetMaxHeight, "女儿墙最大高度"));
      add(var3, "--parapet-per-floor", number(this.parapetPerFloor, "女儿墙每层增量"));
      add(var3, "--parapet-thickness", number(this.parapetThickness, "女儿墙厚度"));
      add(var3, "--shaft-min-lc", integer(this.shaftMinLc, "屋顶机房起始层数"));
      add(var3, "--shaft-inset", number(this.shaftInset, "屋面内缩"));
      add(var3, "--shaft-clearance", number(this.shaftClearance, "机房距边界净距"));
      add(var3, "--shaft-max-len", number(this.shaftMaxLen, "机房最大长边"));
      add(var3, "--shaft-max-wid", number(this.shaftMaxWid, "机房最大短边"));
      add(var3, "--shaft-max-height", number(this.shaftMaxHeight, "机房最大高度"));
      add(var3, "--shaft-center-ratio", number(this.shaftCenterBoxRatio, "中心采样比例"));
      add(var3, "--textures", Boolean.toString(this.texturesBox.isSelected()));
      add(var3, "--building-lod-chain", Boolean.toString(this.buildingLodChainBox.isSelected()));
      add(var3, "--keep-intermediate", Boolean.toString(this.keepIntermediateBox.isSelected()));
      return var3;
   }

   private static void add(List<String> var0, String var1, String var2) {
      var0.add(var1);
      var0.add(var2);
   }

   private static String number(JTextField var0, String var1) {
      String var2 = var0.getText().trim();

      try {
         Double.parseDouble(var2);
         return var2;
      } catch (NumberFormatException var4) {
         throw new IllegalArgumentException(var1 + " 必须是数字");
      }
   }

   private static String integer(JTextField var0, String var1) {
      String var2 = var0.getText().trim();

      try {
         Integer.parseInt(var2);
         return var2;
      } catch (NumberFormatException var4) {
         throw new IllegalArgumentException(var1 + " 必须是整数");
      }
   }

   private void appendLog(String var1) {
      if (SwingUtilities.isEventDispatchThread()) {
         this.logArea.append(var1 + System.lineSeparator());
      } else {
         SwingUtilities.invokeLater(() -> this.appendLog(var1));
      }
   }

   private void showError(String var1) {
      String var2 = var1 != null && !var1.isBlank() ? var1 : "未知错误";
      this.appendLog(var2);
      JOptionPane.showMessageDialog(this.frame, var2, "错误", 0);
   }

   private void show() {
      this.frame.setVisible(true);
   }

   private final class LogOutputStream extends OutputStream {
      private final StringBuilder line = new StringBuilder();

      @Override
      public void write(int var1) {
         if (var1 == 10) {
            ShpTo3dTilesGui.this.appendLog(this.line.toString());
            this.line.setLength(0);
         } else if (var1 != 13) {
            this.line.append((char)var1);
         }
      }

      @Override
      public void flush() {
         if (this.line.length() > 0) {
            ShpTo3dTilesGui.this.appendLog(this.line.toString());
            this.line.setLength(0);
         }
      }
   }
}
