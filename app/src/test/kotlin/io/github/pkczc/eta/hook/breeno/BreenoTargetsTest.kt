package io.github.pkczc.eta.hook.breeno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.util.LinkedList
import java.lang.reflect.Modifier

class BreenoTargetsTest {
    @Test
    fun resolvesRenamedSingletonFieldByItsStaticSelfType() {
        assertSame(RenamedSingleton.a, uniqueBreenoSingletonInstance(RenamedSingleton::class.java))
    }

    @Test
    fun keepsNamedSingletonInsteadOfSelectingAnAlias() {
        assertSame(NamedSingleton.INSTANCE, uniqueBreenoSingletonInstance(NamedSingleton::class.java))
    }

    @Test
    fun retainsNamedSingletonStoredAsItsSupertype() {
        assertSame(GenericNamedSingleton.INSTANCE, uniqueBreenoSingletonInstance(GenericNamedSingleton::class.java))
    }

    @Test
    fun rejectsAmbiguousSingletonFieldsInsteadOfPickingDeclarationOrder() {
        assertNull(uniqueBreenoSingletonInstance(AmbiguousSingleton::class.java))
    }

    @Test
    fun rejectsMissingAndNonStaticSingletonFields() {
        assertNull(uniqueBreenoSingletonInstance(null))
        assertNull(uniqueBreenoSingletonInstance(NonStaticSingleton::class.java))
    }

    @Test
    fun substitutesOnlyNativeCleanupWhileTheRoomIsClaimed() {
        val clean = CleanOperation()
        val other = OtherOperation()
        val noOp = NoOpOperation::class.java.getDeclaredConstructor().apply { isAccessible = true }

        val replacement = suppressBreenoCleanup(clean, CleanOperation::class.java, noOp, claimed = true)
        assertTrue(replacement is NoOpOperation)
        assertNotSame(replacement, suppressBreenoCleanup(clean, CleanOperation::class.java, noOp, claimed = true))
        assertSame(clean, suppressBreenoCleanup(clean, CleanOperation::class.java, noOp, claimed = false))
        assertSame(other, suppressBreenoCleanup(other, CleanOperation::class.java, noOp, claimed = true))
        assertNull(suppressBreenoCleanup(null, CleanOperation::class.java, noOp, claimed = true))
    }

    @Test
    fun resolvesSynchronizedCardMutationByItsPrimitiveBooleanContract() {
        val method = uniqueBreenoInstanceMethod(
            CardMutation::class.java, Void.TYPE.name, Any::class.java.name,
            Boolean::class.javaPrimitiveType!!.name,
        )

        assertEquals("mutate", method?.name)
        assertTrue(Modifier.isSynchronized(requireNotNull(method).modifiers))
    }

    @Test
    fun resolvesRenamedHistoryMethodByItsBoxedBooleanContract() {
        val method = uniqueBreenoInstanceMethod(
            RenamedHistory::class.java,
            LinkedList::class.java.name,
            String::class.java.name,
            Boolean::class.javaObjectType.name,
        )

        assertEquals("newEntry", method?.name)
        assertEquals(listOf("room"), method?.invoke(RenamedHistory(), "room", false))
    }

    @Test
    fun rejectsAmbiguousMethodsInsteadOfPickingDeclarationOrder() {
        assertNull(uniqueBreenoInstanceMethod(AmbiguousResult::class.java, Any::class.java.name))
    }

    @Test
    fun rejectsStaticFactoryWhenLookingForAResultPayloadGetter() {
        assertNull(uniqueBreenoInstanceMethod(StaticResult::class.java, Any::class.java.name))
    }

    @Test
    fun ignoresSyntheticGenericBridge() {
        assertNull(uniqueBreenoInstanceMethod(StringResult::class.java, Any::class.java.name))
        assertEquals(
            "payload",
            uniqueBreenoInstanceMethod(StringResult::class.java, String::class.java.name)
                ?.invoke(StringResult()),
        )
    }

    private class RenamedHistory {
        fun newEntry(roomId: String, animate: Boolean?): LinkedList<String> =
            LinkedList<String>().apply { if (animate == false) add(roomId) }

        fun oldEntry(roomId: String, animate: Boolean): LinkedList<String> =
            LinkedList<String>().apply { if (animate) add(roomId) }
    }

    private class CardMutation {
        @Synchronized
        fun mutate(bean: Any, update: Boolean) = Unit
        fun boxed(bean: Any, update: Boolean?) = Unit
    }

    private class CleanOperation
    private class OtherOperation
    private class NoOpOperation

    private class RenamedSingleton private constructor() {
        companion object {
            val a = RenamedSingleton()
        }
    }

    private class NamedSingleton private constructor() {
        companion object {
            val INSTANCE = NamedSingleton()
            val alias = INSTANCE
        }
    }

    private class AmbiguousSingleton private constructor() {
        companion object {
            val a = AmbiguousSingleton()
            val b = AmbiguousSingleton()
        }
    }

    private class GenericNamedSingleton private constructor() {
        companion object {
            val INSTANCE: Any = GenericNamedSingleton()
        }
    }

    private class NonStaticSingleton(val child: NonStaticSingleton? = null)

    private class AmbiguousResult {
        fun first(): Any = "first"
        fun second(): Any = "second"
    }

    private class StaticResult {
        companion object {
            @JvmStatic
            fun create(): Any = "static"
        }
    }

    private interface GenericResult<T> {
        fun read(): T
    }

    private class StringResult : GenericResult<String> {
        override fun read(): String = "payload"
    }
}
