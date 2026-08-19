package com.picoxr.mrspacetowerdefense.model

enum class MonsterType {
    NORMAL,
    FAST,
    ARMORED,
    SELF_DESTRUCT,
    ACID,
    ELITE,
    BOSS,
}

enum class MonsterBehavior {
    MELEE,
    SELF_DESTRUCT,
    RANGED,
    BOSS,
}

data class MonsterConfig(
    val type: MonsterType,
    val name: String,
    val baseHp: Float,
    val moveSpeed: Float,
    val siegeDamage: Int,
    val killGoldReward: Int,
    val modelResourceName: String,
    val behavior: MonsterBehavior = MonsterBehavior.MELEE,
    val attackRange: Float = 0f,
    val attackIntervalSeconds: Float = 1f,
    val selfDestructDamage: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "Monster name cannot be blank" }
        require(baseHp > 0f) { "Monster base HP must be greater than zero" }
        require(moveSpeed > 0f) { "Monster move speed must be greater than zero" }
        require(siegeDamage >= 0) { "Monster siege damage cannot be negative" }
        require(killGoldReward >= 0) { "Monster reward cannot be negative" }
        require(modelResourceName.isNotBlank()) { "Monster model resource name cannot be blank" }
        require(attackRange >= 0f)
        require(attackIntervalSeconds > 0f)
        require(selfDestructDamage >= 0)
    }
}
