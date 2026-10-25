# 原生层

Java 侧通过 FFM(`java.lang.foreign`)调这三块 C 代码,编译产物放在
`common/src/main/resources/natives/windows-x86_64/`:

| 产物 | 源码 | 作用 |
| --- | --- | --- |
| `seedatlas_xaero.dll` | [DUzzL/Seed-Atlas](https://github.com/DUzzL/Seed-Atlas) 引擎 | 群系采样、结构位置、地图瓦片 |
| `sax_detail.dll` | `sax_detail.c` | 结构分割图 + 箱子 + 战利品预测 |

`sax_detail` 只是薄封装,真正的算法在 [xpple/cubiomes](https://github.com/xpple/cubiomes)
(`features/stronghold.h`、`loot/`、`features/ore.c`)。

## 重建

需要 MSYS2 / MinGW-w64 的 gcc 和一份 xpple/cubiomes 源码树:

```bash
export CUBIOMES_DIR=/path/to/xpcubiomes
bash native/build_natives.sh
```

脚本会编译两个 DLL 并直接写进资源目录(覆盖旧的),然后正常 `./gradlew build` 即可。

ABI 约定写在 `sax_detail.h` / `sax_ore.h` 里:结构体字段顺序和偏移是 Java 侧
`StructureDetail.java` 里 `PIECE_LAYOUT` / `PIECE_TAIL_OFF` 手工对齐的,**改结构体必须两边一起改**,
否则读到的是错位内存(已经踩过一次,差 4 字节)。

## 许可

`seedatlas_xaero.dll` 来自 Seed Atlas(GPL-3.0);cubiomes 为 MIT。两个 DLL 都按 GPL-3.0 一起分发。
