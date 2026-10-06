package com.unciv.logic.automation

enum class ResearchPriority { Auto, Military, Economy }
enum class ProductionPriority { Auto, Army, Production, Gold, Science }
enum class CombatPriority { Auto, Capital, Nearest, Counterattack }
enum class ExpansionPriority { Auto, NewCities, ExistingCities }
enum class AutomationStage { Building, Preparing, Combat }

/** Immutable input to the controlled preview; never attached to GameInfo or the ruleset. */
data class AutomationStrategy(
    val research: ResearchPriority = ResearchPriority.Military,
    val production: ProductionPriority = ProductionPriority.Army,
    val combat: CombatPriority = CombatPriority.Capital,
    val expansion: ExpansionPriority = ExpansionPriority.ExistingCities,
) {
    val signature get() = listOf(research.name, production.name, combat.name, expansion.name).joinToString("/")

    companion object {
        val Balanced = AutomationStrategy(ResearchPriority.Auto, ProductionPriority.Auto,
            CombatPriority.Auto, ExpansionPriority.Auto)
        val Siege = AutomationStrategy()
        val Economy = AutomationStrategy(ResearchPriority.Economy, ProductionPriority.Production,
            CombatPriority.Auto, ExpansionPriority.ExistingCities)
    }
}
