package com.iamcanincan.opticon.runtime

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 反射取成员的薄封装。
 *
 * 只用标准 Java 反射，不碰框架自带的 Helper —— 这样同一份代码在不同实现下都能跑。
 * 统一约定是「取不到就放弃」：所有查找失败一律返回 null，
 * 因为代码跑在 SystemUI 进程里，一次反射失手不该把系统界面带崩。
 *
 * ## 两套入口，按调用频率选
 * - **按名字**（`readField(target, "mContext")` 这类）：每次调用都要 getDeclaredField /
 *   getDeclaredMethod 现找一次，只适合「一辈子跑几次」的地方（装钩、解码图标字段）。
 * - **先解析再用**（[findField] / [findFieldByType] / [findMethod] + 收 [Field]、[Method]
 *   的那几个重载）：查找只做一次，运行期只剩 field.get / method.invoke。
 *   钩子体是**每条通知、每次图标绘制**都要跑的，那里必须用这一套，
 *   否则等于把一次反射查找塞进热路径反复烧。
 *   find* 三个本身不抛异常（找不到返回 null），调用方仍应各自包一层 runCatching ——
 *   装钩期抛出去会让整批钩子装不上，比不缓存糟得多。
 */
object MemberLookup {

    fun findClass(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { classLoader.loadClass(name) }.getOrNull()

    /**
     * 按名字读字段 —— 这是**二道兜底**（首选是装钩期 [findField] 解析好、闭包捕获）。
     *
     * ⚠ 沿父类链找，与 [findField] 同一口径。原来这一版只查 `target.javaClass` 自己一层，
     *   于是出现过一种只有兜底会撞上的坏法：ROM 把 `mContext` / `mCurrentSetColor` 这类字段
     *   挪到父类时，[findField] 那一步**是能找到的**，兜底反而找不到 ——
     *   两条路对同一个字段给出相反的答案，排查时会误判成"字段不存在"。
     *   主路径与兜底必须说同一套话；要快就解析一次（见类文档的两套入口）。
     */
    fun readField(target: Any, name: String): Any? =
        findField(target.javaClass, name)?.let { runCatching { it.get(target) }.getOrNull() }

    /**
     * 读**已解析好的**字段。
     *
     * 与字符串版的唯一区别就是不再 getDeclaredField；[Field] 在解析时就 setAccessible 过了。
     * 契约照旧：取不到一律返回 null，绝不把异常对象当返回值传出去。
     */
    fun readField(target: Any, field: Field): Any? = runCatching { field.get(target) }.getOrNull()

    /**
     * 按字段名解析出 [Field]，沿父类链向上找，找不到返回 null（**不抛**）。
     *
     * 拿到就 setAccessible，交给 [readField] / [writeField] 的 Field 版用。
     */
    fun findField(clazz: Class<*>, name: String): Field? = runCatching {
        generateSequence(clazz) { it.superclass }.firstNotNullOfOrNull { c ->
            // ⚠ setAccessible 必须留在**内层** runCatching 里，与 [findMethod] 一致：
            //   放到外面的话，某一层设不进去会直接穿透到最外层那个 runCatching、函数返回 null ——
            //   于是「这一层不行就继续往上找」这条契约只对 findMethod 成立，字段这边会提前放弃。
            runCatching { c.getDeclaredField(name).also { it.isAccessible = true } }.getOrNull()
        }
    }.getOrNull()

    /**
     * 按字段类型解析出 [Field]，语义同 [readFieldByType]（沿父类链，找不到返回 null）。
     * SystemUI 内部类改名的频率远高于改类型，靠类型找更耐版本变化。
     */
    fun findFieldByType(clazz: Class<*>, type: Class<*>): Field? = runCatching {
        generateSequence(clazz) { it.superclass }.firstNotNullOfOrNull { c ->
            // setAccessible 同样留在内层（理由见 [findField]）
            runCatching {
                c.declaredFields.firstOrNull { it.type == type }?.also { it.isAccessible = true }
            }.getOrNull()
        }
    }.getOrNull()

    /**
     * 按字段类型而不是名字取值。
     * SystemUI 内部类改名的频率远高于改类型，靠类型找更耐版本变化。
     *
     * 注意每次调用都要把目标类的字段表扫一遍，热路径上请改用 [findFieldByType] 解析一次。
     */
    fun readFieldByType(target: Any, type: Class<*>): Any? =
        findFieldByType(target.javaClass, type)?.let { runCatching { it.get(target) }.getOrNull() }

    /**
     * 写字段，返回是否真的写进去了。
     *
     * 字段改名或不可访问时**静默失败**是最难查的一类问题（表现为「功能没生效但日志干净」），
     * 所以把结果交回调用方，由它决定要不要打日志。
     */
    fun writeField(target: Any, name: String, value: Any?): Boolean =
        findField(target.javaClass, name)?.let { runCatching { it.set(target, value) }.isSuccess } == true

    /**
     * 写**已解析好的**字段，返回值含义同 [writeField] 的字符串版（是否真的写进去了）。
     *
     * ⚠ 一律用 `Field.set`，别换成 `setBoolean` / `setInt` 那类原始类型快捷方法：
     * 字段声明成装箱类型时快捷方法会抛 `IllegalArgumentException("Not a primitive field")`，
     * 而这里的失败全被吞成 false —— 表现就是「功能没生效但日志干净」。
     * 真机踩过这一脚（累计 9994 次静默失败），原委记在 IconHooks 里挂 drawFullBleed 那段注释上。
     * `set()` 会自动拆箱，装箱类型和基本类型都吃得下。
     */
    fun writeField(target: Any, field: Field, value: Any?): Boolean = runCatching {
        field.set(target, value)
        true
    }.getOrDefault(false)

    /**
     * 反射调用实例方法。
     *
     * **失败一律返回 null**，绝不把异常对象当返回值传出去 —— 那会让调用方的
     * `!= null` / `!!` 判断误以为调用成功了，问题被推迟到更难查的地方才炸。
     * 契约与 [invokeStatic] 保持一致，也符合本类「取不到就放弃」的总约定。
     */
    fun invoke(
        target: Any,
        name: String,
        args: Array<out Any?> = emptyArray(),
        vararg paramTypes: Class<*>
    ): Any? = try {
        val m = declaredMethod(target.javaClass, name, *paramTypes)
        m.isAccessible = true
        m.invoke(target, *args)
    } catch (_: Throwable) {
        null
    }

    /**
     * 调用**已解析好的**实例方法（[findMethod] 的产物），契约同字符串版。
     *
     * 拿 [Method] 去 invoke 照样走虚方法分派：解析时用的是父类，运行期对象是子类时
     * 依然会调到子类的 override，与「在子类上现找一次」的结果一致。
     */
    fun invoke(
        target: Any,
        method: Method,
        args: Array<out Any?> = emptyArray()
    ): Any? = runCatching { method.invoke(target, *args) }.getOrNull()

    fun invokeStatic(
        clazz: Class<*>,
        name: String,
        args: Array<out Any?> = emptyArray(),
        vararg paramTypes: Class<*>
    ): Any? = try {
        val m: Method = clazz.getDeclaredMethod(name, *paramTypes)
        m.isAccessible = true
        m.invoke(null, *args)
    } catch (_: Throwable) {
        null
    }

    /** 调用**已解析好的**静态方法，契约同 [invokeStatic] 的按名版 */
    fun invokeStatic(
        method: Method,
        args: Array<out Any?> = emptyArray()
    ): Any? = runCatching { method.invoke(null, *args) }.getOrNull()

    /** 沿父类链向上找声明的方法 */
    fun declaredMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method {
        var c: Class<*>? = clazz
        while (c != null) {
            try {
                return if (paramTypes.isEmpty()) c.getDeclaredMethod(name)
                else c.getDeclaredMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        throw NoSuchMethodException(name)
    }

    /**
     * [declaredMethod] 的「不抛」版：找不到返回 null。
     *
     * declaredMethod 每上一层就用一次 NoSuchMethodException 探路，装钩期无所谓，
     * 但放进钩子体就是每条通知造几个异常对象（填栈很贵）。装钩时用这个把 [Method] 定下来。
     * setAccessible 也一起算进失败条件：那一层设不进去就继续往上找，
     * 全找不到返回 null，调用方照旧退回按名现查。
     */
    fun findMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? = runCatching {
        generateSequence(clazz) { it.superclass }.firstNotNullOfOrNull { c ->
            runCatching {
                val m = if (paramTypes.isEmpty()) c.getDeclaredMethod(name)
                else c.getDeclaredMethod(name, *paramTypes)
                m.isAccessible = true
                m
            }.getOrNull()
        }
    }.getOrNull()

    /**
     * 只要签名里出现过给定类型就命中，不要求顺序和个数完全一致。
     * SystemUI 给同一方法增删参数是家常便饭，精确匹配反而容易挂不上。
     */
    fun methodWithParams(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name) continue
                m.isAccessible = true
                if (params.isEmpty()) return m
                val types = m.parameterTypes.toList()
                if (types.isEmpty()) continue
                if (params.all { types.contains(it) }) return m
            }
            c = c.superclass
        }
        return null
    }
}
