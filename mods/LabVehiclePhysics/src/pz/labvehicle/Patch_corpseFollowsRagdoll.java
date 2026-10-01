package pz.labvehicle;

import java.lang.reflect.Method;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Этап 1.8: труп едет вместе с машиной, а не телепортируется к месту удара.
 *
 * Что происходит в ванили. Сбитый на большой скорости зомби умирает от первого удара
 * (здоровье 1.8-2.1, урон от наезда до 10 — смерть с одного касания задумана), но его
 * рэгдолл продолжает симулироваться и тело тащит на капоте. При этом ЛОГИЧЕСКАЯ координата
 * персонажа за рэгдоллом не идёт: её подтягивает анимационная система живого персонажа
 * (AnimationPlayer: deferredMovement = (позиция_рэгдолла - позиция_персонажа) * вес),
 * а у мёртвого эта ветка не работает. Когда рэгдолл заканчивается, труп проявляется
 * на своей старой координате — выглядит как телепорт назад, к месту столкновения.
 *
 * Чиним: пока рэгдолл активен, сами переносим логическую координату мёртвого персонажа
 * в позицию, которую считает рэгдолл. Используем штатные методы игры:
 * setPosition(x,y,z) + setCurrentSquareFromPosition(), второй перерегистрирует объект
 * на нужной клетке мира — без этого тело осталось бы числиться на старой клетке
 * и, например, не открывалось бы при обыске.
 *
 * Живых не трогаем: ими занимается анимационная система, и вмешиваться туда незачем.
 *
 * <h2>Высоту не переносим (26.09.2026)</h2>
 * Высота в позиции рэгдолла — это поза, а не положение тела в мире: упав, зомби
 * опускает таз на треть метра, но остаётся на той же клетке. Раньше при таком
 * провале ниже нуля перенос отклонялся целиком — 775 раз за сессию, — и тело на
 * эти кадры переставало следовать и по горизонтали. Теперь x и y берём у рэгдолла,
 * а высоту оставляем свою. Машины в PZ ездят только по нулевому этажу
 * ({@code AddVehicleCommand}: "Z coordinate must be 0 for now"), так что сбитое тело
 * этаж не меняет, а высоту на клетке игра выставляет сама.
 *
 * <h2>Чего здесь делать НЕ надо</h2>
 * 26.09.2026 я решил, что наша запись позиции замыкает петлю через таз, и перевёл
 * патч на приращения с вычетом собственного прошлого хода. Это было неверно:
 * {@code RagdollController.calculateRagdollWorldTransform} привязывает рэгдолл к
 * позиции персонажа, и положение таза остаётся настоящим положением тела — наши
 * записи его не портят. Вычитая несуществующую петлю, патч вёл персонажа за телом
 * вполсилы: сдвиг, ноль, сдвиг, ноль. В игре это выглядело как двоение — рэгдолл
 * рисуется в одном месте, труп в отстающем, — а после конца рэгдолла труп
 * оказывался далеко позади. Проверка моделью этого не поймала: модель проверяла
 * мою же гипотезу, а не игру. Коммит 1651e00, откат — следующий.
 */
@Patch(className = "zombie.core.physics.RagdollController", methodName = "postUpdate", warmUp = true)
public class Patch_corpseFollowsRagdoll {

    @Patch.OnExit
    public static void exit(@Patch.This Object ragdoll) {
        if (!LabGate.active() || !LabSettings.corpseFollows()) {
            return;
        }
        Impl.sync(ragdoll);
    }

    public static final class Impl {
        /** Не дёргаем клетку, если сдвиг меньше этого (в тайлах). */
        public static final float MIN_MOVE = 0.05f;
        /**
         * Потолок скачка за кадр, в тайлах (тайл примерно метр).
         *
         * Было 20, то есть сорок метров за кадр. Лог показал, что рэгдолл в это окно
         * упирался окно за окном: max 19.98, 19.85, 19.78 при среднем шаге 3 тайла.
         * Такие значения — мусор, а мы их применяли, тело улетало за пределы
         * подгруженных чанков и молча удалялось (подробности у {@link #hasSquare}).
         *
         * Четыре тайла за кадр — это 864 км/ч при 60 fps и 216 км/ч даже при 15 fps,
         * то есть для трупа на бампере здесь всё ещё огромный запас.
         * Сколько на самом деле отсекается, покажет счётчик rejFar в сводке.
         */
        public static final float MAX_JUMP = 4.0f;

        public static volatile boolean broken = false;
        public static Method rGetChar, rX, rY;
        public static Method cIsDead, cGetX, cGetY, cGetZ, cSetPosition, cSetSquare;
        /** Для проверки, что в точке назначения вообще есть клетка мира. */
        public static Method cGetCell, cellGetSquare;
        /** Приватное поле IsoGameCharacter.diedBody — сам объект трупа, геттера нет. */
        public static java.lang.reflect.Field cDiedBody;
        public static Method bSetPosition, bSetSquare;
        public static long corpseMoves = 0L;
        /**
         * Ссылку на труп приходится кэшировать: VirtualZombieManager возвращает объект
         * зомби в пул и зовёт clearDiedBody(), так что поле diedBody видно буквально
         * один кадр из полутора сотен. Ловим, пока видно, и дальше двигаем по своей ссылке.
         */
        public static final java.util.Map<Object, Object> CORPSE_CACHE = new java.util.WeakHashMap<Object, Object>();
        public static final java.util.Map<Object, Long> CORPSE_SINCE = new java.util.WeakHashMap<Object, Long>();
        /** Дольше этого труп за рэгдоллом не таскаем. */
        public static final long CORPSE_MAX_NANOS = 8_000_000_000L;
        public static boolean logged = false;
        public static long moves = 0L;
        public static double sumDist = 0.0;
        public static double maxDist = 0.0;
        public static long lastReportNanos = 0L;
        /** Отклонённые переносы, по причинам. Нужны, чтобы видеть, как часто рэгдолл врёт. */
        public static long rejNaN = 0L;
        public static long rejFar = 0L;
        public static long rejNoSquare = 0L;
        public static boolean noSquareLogged = false;

        public static void sync(Object ragdoll) {
            if (broken || ragdoll == null) {
                return;
            }
            try {
                if (rGetChar == null) {
                    initRagdoll(ragdoll.getClass());
                }
                Object chr = rGetChar.invoke(ragdoll);
                if (chr == null) {
                    return;
                }
                if (cIsDead == null) {
                    initChar(chr.getClass());
                }
                if (!((Boolean) cIsDead.invoke(chr)).booleanValue()) {
                    // объект зомби мог уйти в пул и переиспользоваться под живого —
                    // тогда старый труп к нему отношения не имеет
                    synchronized (CORPSE_CACHE) {
                        CORPSE_CACHE.remove(chr);
                        CORPSE_SINCE.remove(chr);
                    }
                    return;   // живого ведёт анимационная система
                }
                // Сводка идёт ДО разбора: если рэгдолл врёт и все переносы отклоняются,
                // до конца метода мы не доходим и молчали бы как раз тогда, когда важнее
                // всего это видеть.
                report();
                float nx = ((Float) rX.invoke(ragdoll)).floatValue();
                float ny = ((Float) rY.invoke(ragdoll)).floatValue();
                if (Float.isNaN(nx) || Float.isNaN(ny)) {
                    rejNaN++;
                    return;
                }
                float ox = ((Float) cGetX.invoke(chr)).floatValue();
                float oy = ((Float) cGetY.invoke(chr)).floatValue();
                float oz = ((Float) cGetZ.invoke(chr)).floatValue();
                // Высоту берём свою: у рэгдолла это поза, а не этаж (см. заголовок класса).
                // Но и своя бывает ниже нуля: пока рэгдолл ещё живой, ваниль сама двигает
                // персонажа за ним по высоте (doDeferredMovementFromRagdoll: setZ(getZ() + dz)),
                // и таз утягивает его под пол. В логе: "move a body to 11701.3, 6805.0, -0.1
                // where the world has no square" — тело целое, но не двигалось. Отрицательную
                // высоту считаем полом; второй этаж не страдает, меняется только минус.
                float nz = oz < 0.0f ? 0.0f : oz;
                float dx = nx - ox, dy = ny - oy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < MIN_MOVE) {
                    return;
                }
                if (dist > MAX_JUMP) {
                    rejFar++;
                    return;
                }
                if (!hasSquare(chr, nx, ny, nz)) {
                    rejNoSquare++;
                    return;
                }
                cSetPosition.invoke(chr, Float.valueOf(nx), Float.valueOf(ny), Float.valueOf(nz));
                cSetSquare.invoke(chr);
                moves++;
                if (!logged) {
                    logged = true;
                    Log.debug("[LabVehiclePhysics] corpse moved with its ragdoll for the first time - no teleport expected");
                }

                // ГЛАВНОЕ: труп создаётся в момент смерти на месте удара и дальше координат
                // не меняет — своего рэгдолла у него нет. Двигаем его тем же путём.
                Object corpse = cDiedBody.get(chr);
                long nowNanos = System.nanoTime();
                synchronized (CORPSE_CACHE) {
                    if (corpse != null) {
                        if (CORPSE_CACHE.put(chr, corpse) == null) {
                            CORPSE_SINCE.put(chr, Long.valueOf(nowNanos));
                        }
                    } else {
                        Long since = CORPSE_SINCE.get(chr);
                        if (since != null && nowNanos - since.longValue() < CORPSE_MAX_NANOS) {
                            corpse = CORPSE_CACHE.get(chr);   // поле уже обнулили — берём своё
                        } else if (since != null) {
                            CORPSE_CACHE.remove(chr);
                            CORPSE_SINCE.remove(chr);
                        }
                    }
                }
                if (corpse != null) {
                    if (bSetPosition == null) {
                        bSetPosition = corpse.getClass().getMethod("setPosition", float.class, float.class, float.class);
                        bSetSquare = corpse.getClass().getMethod("setCurrentSquareFromPosition");
                        Log.debug("[LabVehiclePhysics] corpse object found (" + corpse.getClass().getSimpleName()
                                + ") - moving it as well");
                    }
                    bSetPosition.invoke(corpse, Float.valueOf(nx), Float.valueOf(ny), Float.valueOf(nz));
                    bSetSquare.invoke(corpse);
                    corpseMoves++;
                }
                sumDist += dist;
                if (dist > maxDist) {
                    maxDist = dist;
                }
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the corpse patch, disabling: " + t);
            }
        }

        /**
         * Есть ли в точке назначения клетка мира.
         *
         * Зачем это вообще. {@code setCurrentSquareFromPosition()} присваивает результат
         * поиска БЕЗУСЛОВНО, в том числе null:
         * <pre>
         * IsoGridSquare current = this.getCell().getGridSquare(x1, y1, z1);
         * if (current == null) {
         *     for (int n = PZMath.fastfloor(z1); n &gt;= 0; n--) {   // только вниз, тот же x,y
         *         current = this.getCell().getGridSquare(x1, y1, n);
         *         if (current != null) break;
         *     }
         * }
         * this.setCurrent(current);
         * </pre>
         * Запасной поиск спасает только от промаха по вертикали. Если x,y вылетели за
         * подгруженные чанки, null вернут все попытки. А дальше, в {@code IsoZombie.update}:
         * <pre>
         * if (this.current == null &amp;&amp; (!GameClient.client || !this.isRemoteZombie())) {
         *     this.removeFromWorld();
         *     this.removeFromSquare();
         * }
         * </pre>
         * Ни смерти, ни трупа, ни звука — объект вычёркивается молча.
         *
         * Это и была пропажа зомби при наезде на толпу: сбитый умирает с первого касания
         * (урон до 10 при здоровье 1.8-2.1), сразу попадает под этот патч, и часть тел
         * мы сами уносили в пустоту. В толпе рэгдоллы проникают друг в друга, Bullet
         * расталкивает их рывками, выбросов больше — потому и заметно именно там.
         *
         * Повторяем ту же лестницу вниз, что и игра, чтобы не отказывать в переносе там,
         * где ваниль справилась бы сама.
         */
        public static boolean hasSquare(Object chr, float nx, float ny, float nz) throws Exception {
            Object cell = cGetCell.invoke(chr);
            if (cell == null) {
                return false;
            }
            if (cellGetSquare.invoke(cell, Double.valueOf(nx), Double.valueOf(ny), Double.valueOf(nz)) != null) {
                return true;
            }
            for (int n = (int) Math.floor((double) nz); n >= 0; n--) {
                if (cellGetSquare.invoke(cell, Double.valueOf(nx), Double.valueOf(ny), Double.valueOf(n)) != null) {
                    return true;
                }
            }
            if (!noSquareLogged) {
                noSquareLogged = true;
                Log.debug(String.format(
                        "[LabVehiclePhysics] ragdoll asked to move a body to %.1f, %.1f, %.1f where the world has no "
                        + "square - move skipped (before this fix such a body was silently deleted)", nx, ny, nz));
            }
            return false;
        }

        private static synchronized void initRagdoll(Class<?> rc) throws Exception {
            if (rGetChar != null) {
                return;
            }
            rX = rc.getMethod("getDesiredCharacterPositionX");
            rY = rc.getMethod("getDesiredCharacterPositionY");
            rGetChar = rc.getMethod("getGameCharacterObject");
        }

        private static synchronized void initChar(Class<?> cc) throws Exception {
            if (cIsDead != null) {
                return;
            }
            cIsDead = cc.getMethod("isDead");
            cGetX = cc.getMethod("getX");
            cGetY = cc.getMethod("getY");
            cGetZ = cc.getMethod("getZ");
            cSetPosition = cc.getMethod("setPosition", float.class, float.class, float.class);
            cSetSquare = cc.getMethod("setCurrentSquareFromPosition");
            cGetCell = cc.getMethod("getCell");
            cellGetSquare = Class.forName("zombie.iso.IsoCell", false, cc.getClassLoader())
                    .getMethod("getGridSquare", double.class, double.class, double.class);
            Class<?> c = cc;
            while (c != null && cDiedBody == null) {
                try {
                    cDiedBody = c.getDeclaredField("diedBody");
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            if (cDiedBody == null) {
                throw new NoSuchFieldException("diedBody");
            }
            cDiedBody.setAccessible(true);
            Log.debug("[LabVehiclePhysics] corpse patch ready: the dead body follows its ragdoll ("
                    + cc.getName() + ")");
        }

        public static void report() {
            long now = System.nanoTime();
            if (lastReportNanos == 0L) {
                lastReportNanos = now;
                return;
            }
            if (now - lastReportNanos < 15_000_000_000L) {
                return;
            }
            lastReportNanos = now;
            long rejected = rejNaN + rejFar + rejNoSquare;
            if (moves == 0L && rejected == 0L) {
                return;
            }
            Log.debug(String.format(
                    "[LabVehiclePhysics] ragdoll follow, last 15 s: bodies %d, CORPSES %d (caught in total %d), "
                    + "mean step %.2f tiles, max %.2f; rejected %d (no square %d, "
                    + "farther than %.1f tiles %d, NaN %d)",
                    moves, corpseMoves, Patch_catchCorpse.Impl.caught,
                    moves > 0L ? sumDist / moves : 0.0, maxDist,
                    rejected, rejNoSquare, MAX_JUMP, rejFar, rejNaN));
            moves = 0L;
            corpseMoves = 0L;
            sumDist = 0.0;
            maxDist = 0.0;
            rejNaN = 0L;
            rejFar = 0L;
            rejNoSquare = 0L;
        }
    }
}
