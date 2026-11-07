package com.iamcanincan.opticon.ui

import com.iamcanincan.opticon.R

/**
 * 二级页（2026-11-07 主人：「可以做个二级界面的……一个界面不要太长」）。
 *
 * 一级页只有四张，且每张都短：主页 / 功能 / 统计 / 设置。凡是"一组同质内容"
 * （一个功能的开关与说明、作用域清单、启用步骤、运维操作、关于）都下沉到二级页，
 * 由一级页的一行入口点进去，顶栏左上角给返回。
 *
 * ⚠ 只列"点进去是哪一页"，不持有任何状态：渲染在外壳（`OpticonApp`）里按这个枚举分派，
 *   页面之间因此不互相引用，加一页只要动这里 + 外壳那条 when。
 */
internal enum class SubPage(val titleRes: Int) {
  CROP(R.string.tab_crop),
  MONO(R.string.tab_mono),
  NOTIFY(R.string.tab_notify),
  SCOPE(R.string.section_scope),
  SETUP(R.string.section_steps),
  TOOLS(R.string.section_tools),
  ABOUT(R.string.tab_about),
}
