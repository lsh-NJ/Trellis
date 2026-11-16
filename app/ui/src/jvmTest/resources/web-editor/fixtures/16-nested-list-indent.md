# 缩进不足的嵌套列表

1. 查看所有：
  `env` 或 `printenv`

2. 查看单个变量（使用`echo` 命令，需要在变量名前加\$符号）:
  如`echo $PATH` `echo $HOME`

## 修改

1. 临时修改（直接使用`export`命令）：
  `export MY_VARIABLE="my_value"`

2. 永久修改：

  1. 先检查终端
    `echo $0`:`zsh`-编辑`~/.zshrc`；`bash`编辑`~/.bash_profile`

  2. 打开对应文件（如`open -e .zshrc`）添加内容：
    `export KEY="value 1"`（如果值包含空格需要使用引号括起来）

1. 尾巴
65

行内代码后连字符：`zsh`-编辑；空格间的下划线：符号 & % _ 混排。
