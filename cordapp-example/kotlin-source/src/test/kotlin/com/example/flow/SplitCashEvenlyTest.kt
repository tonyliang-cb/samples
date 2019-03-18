package com.example.flow

import net.corda.core.contracts.Amount
import net.corda.core.contracts.Issued
import net.corda.core.contracts.PartyAndReference
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.contracts.asset.Cash
import net.corda.testing.internal.chooseIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.Currency

class SplitCashEvenlyTest {
    private lateinit var network: MockNetwork
    private lateinit var owner: StartedMockNode
    private lateinit var issuer: StartedMockNode

    val Long.THB
        get() = Amount.fromDecimal(
            displayQuantity = BigDecimal.valueOf(this),
            token = Issued(
                PartyAndReference(
                    issuer.info.chooseIdentity(),
                    OpaqueBytes.of(0)
                ),
                Currency.getInstance("THB")
            )
        )


    fun issueCash(): StateAndRef<Cash.State> {
        val tx = TransactionBuilder(network.defaultNotaryIdentity)
        val ci = Cash().generateIssue(tx, 100L.THB, owner.info.chooseIdentity(), network.defaultNotaryIdentity)
        val stx = issuer.services.signInitialTransaction(tx, ci)
        issuer.runFlow(FinalityFlow(stx))

        return owner.transaction {
            val result = owner.services.vaultService.queryBy<Cash.State>().states
            assertEquals(1, result.size)
            assertEquals(100L.THB, result.single().state.data.amount)
            result.single()
        }
    }

    @Test
    fun `cash asset cannot be evenly split`() {
        val input = issueCash()
        val tx = TransactionBuilder(network.defaultNotaryIdentity)
        tx.addCommand(Cash.Commands.Move(), owner.info.chooseIdentity().owningKey)
        tx.addInputState(input)
        tx.addOutputState(input.state.data.copy(amount = 50L.THB), Cash.PROGRAM_ID)
        tx.addOutputState(input.state.data.copy(amount = 50L.THB), Cash.PROGRAM_ID)

        // make sure the outputs are distinguishable before notartising
        assertEquals(2, tx.outputStates().size)

        val stx2 = owner.services.signInitialTransaction(tx, owner.info.chooseIdentity().owningKey)
        owner.runFlow(FinalityFlow(stx2))

        owner.transaction {
            val result = owner.services.vaultService.queryBy<Cash.State>().states

            // the tx is committed!!! But only ONE cash state is unconsumed now.
            assertEquals(1, result.size)
            assertEquals(50L.THB, result.first().state.data.amount)
            assertEquals(50L.THB, result.last().state.data.amount)
        }

        // additional findings
        owner.transaction {
            val resultAll = owner.services.vaultService.queryBy<Cash.State>(
                QueryCriteria.VaultQueryCriteria(
                Vault.StateStatus.ALL)).states
            // it's gone in the vault entirely
            assertEquals(2, resultAll.size)
        }
    }

    @Test
    fun `cash asset can be unevenly split`() {
        val input = issueCash()
        val tx = TransactionBuilder(network.defaultNotaryIdentity)
        tx.addCommand(Cash.Commands.Move(), owner.info.chooseIdentity().owningKey)
        tx.addInputState(input)
        tx.addOutputState(input.state.data.copy(amount = 30L.THB), Cash.PROGRAM_ID)
        tx.addOutputState(input.state.data.copy(amount = 70L.THB), Cash.PROGRAM_ID)

        // make sure the outputs are distinguishable before notartising
        assertEquals(2, tx.outputStates().size)

        val stx2 = owner.services.signInitialTransaction(tx, owner.info.chooseIdentity().owningKey)
        owner.runFlow(FinalityFlow(stx2))

        owner.transaction {
            val result = owner.services.vaultService.queryBy<Cash.State>().states
            assertEquals(2, result.size)
            assertEquals(30L.THB, result.first().state.data.amount)
            assertEquals(70L.THB, result.last().state.data.amount)
        }
    }

    @Before
    fun setup() {
        network = MockNetwork(listOf("net.corda.finance"))
        owner = network.createPartyNode(CordaX500Name("HSBC", "Thailand", "TH"))
        issuer = network.createPartyNode(CordaX500Name("BOT", "Thailand", "TH")) // Central Bank
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    fun <T> StartedMockNode.runFlow(logic: FlowLogic<T>): T {
        val future = this.startFlow(logic)
        network.runNetwork()
        return future.getOrThrow()
    }
}